begin;

alter table public.patrol_reservations
    add column if not exists scheduled_window_end timestamptz,
    add column if not exists expires_at timestamptz,
    add column if not exists claimed_at timestamptz,
    add column if not exists execution_id uuid references public.patrol_executions(id) on delete restrict;

create index if not exists patrol_reservations_expiry_idx
    on public.patrol_reservations(organization_id, expires_at)
    where claimed_at is null;

create table if not exists public.execution_checkpoint_plan (
    organization_id uuid not null references public.organizations(id) on delete restrict,
    execution_id uuid not null references public.patrol_executions(id) on delete restrict,
    checkpoint_id uuid not null references public.checkpoints(id) on delete restrict,
    sequence integer not null check (sequence > 0),
    primary key (execution_id, checkpoint_id),
    unique (execution_id, sequence)
);

create index if not exists execution_checkpoint_plan_org_idx
    on public.execution_checkpoint_plan(organization_id, execution_id);

alter table public.execution_checkpoint_plan enable row level security;
revoke all on public.execution_checkpoint_plan from public, anon;
grant select on public.execution_checkpoint_plan to authenticated;
grant select, insert on public.execution_checkpoint_plan to service_role;

create policy tenant_read on public.execution_checkpoint_plan
for select to authenticated
using (organization_id = public.current_organization_id());

create trigger no_delete_execution_checkpoint_plan
before delete on public.execution_checkpoint_plan
for each row execute function public.prevent_immutable_delete();

create or replace function public.reserve_current_patrol(
    p_organization_id uuid,
    p_schedule_id uuid,
    p_user_id uuid,
    p_device_id uuid
)
returns jsonb
language plpgsql
security invoker
set search_path = public
as $$
declare
    schedule_row public.patrol_schedules%rowtype;
    timezone_name text;
    local_now timestamp without time zone;
    local_date date;
    local_time time;
    crosses_midnight boolean;
    window_start_local timestamp without time zone;
    window_end_local timestamp without time zone;
    window_start timestamptz;
    window_end timestamptz;
    existing public.patrol_reservations%rowtype;
    lease_expiry timestamptz := now() + interval '5 minutes';
begin
    select timezone into timezone_name
    from public.organizations
    where id = p_organization_id and active;

    if timezone_name is null then
        raise exception using errcode = 'P0001', message = 'ORGANIZATION_NOT_FOUND';
    end if;

    select * into schedule_row
    from public.patrol_schedules
    where id = p_schedule_id
      and organization_id = p_organization_id
      and active
      and archived_at is null
      and fixed_slot between 1 and 4;

    if not found then
        raise exception using errcode = 'P0001', message = 'PATROL_NOT_AVAILABLE';
    end if;

    if not exists (
        select 1 from public.app_users
        where id = p_user_id
          and organization_id = p_organization_id
          and role = 'GATEKEEPER'
          and active
          and archived_at is null
    ) then
        raise exception using errcode = 'P0001', message = 'GATEKEEPER_NOT_AUTHORIZED';
    end if;

    if not exists (
        select 1 from public.devices
        where id = p_device_id
          and organization_id = p_organization_id
          and active
          and archived_at is null
    ) then
        raise exception using errcode = 'P0001', message = 'DEVICE_NOT_AUTHORIZED';
    end if;

    local_now := now() at time zone timezone_name;
    local_date := local_now::date;
    local_time := local_now::time;
    crosses_midnight := schedule_row.end_time <= schedule_row.start_time;

    if crosses_midnight
       and local_time <= schedule_row.end_time + make_interval(mins => schedule_row.end_tolerance_minutes) then
        local_date := local_date - 1;
    end if;

    window_start_local := local_date + schedule_row.start_time;
    window_end_local := (local_date + case when crosses_midnight then 1 else 0 end) + schedule_row.end_time;
    window_start := window_start_local at time zone timezone_name;
    window_end := window_end_local at time zone timezone_name;

    if now() < window_start - make_interval(mins => schedule_row.start_tolerance_minutes)
       or now() > window_end + make_interval(mins => schedule_row.end_tolerance_minutes) then
        raise exception using errcode = 'P0001', message = 'PATROL_OUTSIDE_WINDOW';
    end if;

    insert into public.patrol_reservations(
        organization_id, schedule_id, scheduled_window_start, scheduled_window_end,
        user_id, device_id, reserved_at, expires_at
    ) values (
        p_organization_id, p_schedule_id, window_start, window_end,
        p_user_id, p_device_id, now(), lease_expiry
    )
    on conflict (schedule_id, scheduled_window_start) do nothing;

    select * into existing
    from public.patrol_reservations
    where schedule_id = p_schedule_id
      and scheduled_window_start = window_start
    for update;

    if existing.claimed_at is null
       and coalesce(existing.expires_at, existing.reserved_at + interval '5 minutes') < now()
       and (existing.user_id <> p_user_id or existing.device_id <> p_device_id) then
        update public.patrol_reservations
        set user_id = p_user_id,
            device_id = p_device_id,
            reserved_at = now(),
            expires_at = lease_expiry,
            scheduled_window_end = window_end
        where id = existing.id
        returning * into existing;
    end if;

    if existing.user_id <> p_user_id or existing.device_id <> p_device_id then
        return jsonb_build_object(
            'reserved', false,
            'conflict', true,
            'message', 'Esta ronda já foi iniciada ou está reservada por outro porteiro.',
            'scheduled_window_start_ms', floor(extract(epoch from window_start) * 1000)::bigint,
            'reserved_by_user_id', existing.user_id
        );
    end if;

    if existing.claimed_at is null then
        update public.patrol_reservations
        set expires_at = lease_expiry,
            scheduled_window_end = window_end
        where id = existing.id;
    end if;

    return jsonb_build_object(
        'reserved', true,
        'conflict', false,
        'claimed', existing.claimed_at is not null,
        'scheduled_window_start_ms', floor(extract(epoch from window_start) * 1000)::bigint,
        'scheduled_window_end_ms', floor(extract(epoch from window_end) * 1000)::bigint
    );
end;
$$;

revoke all on function public.reserve_current_patrol(uuid, uuid, uuid, uuid) from public, anon, authenticated;
grant execute on function public.reserve_current_patrol(uuid, uuid, uuid, uuid) to service_role;

commit;
