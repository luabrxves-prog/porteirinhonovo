begin;

create table if not exists public.patrol_occurrence_reservations (
    id uuid primary key default gen_random_uuid(),
    schedule_window_id uuid not null references public.patrol_schedule_windows(id) on delete restrict,
    scheduled_for timestamptz not null,
    guard_id uuid not null references public.guards(id) on delete restrict,
    device_id uuid not null references public.devices(id) on delete restrict,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_at timestamptz not null default now(),
    unique (schedule_window_id, scheduled_for)
);

create index if not exists patrol_occurrence_reservations_guard_idx
    on public.patrol_occurrence_reservations(guard_id, scheduled_for desc);

alter table public.patrol_occurrence_reservations enable row level security;
revoke all on public.patrol_occurrence_reservations from public, anon, authenticated;
grant select, insert, update on public.patrol_occurrence_reservations to service_role;

create or replace function public.reserve_current_patrol_occurrence(
    p_device_id uuid,
    p_guard_id uuid,
    p_patrol_template_id uuid
)
returns table(
    reservation_id uuid,
    schedule_window_id uuid,
    scheduled_for timestamptz,
    available_until timestamptz,
    patrol_name text
)
language plpgsql
security invoker
set search_path = public
as $$
declare
    candidate record;
    current_reservation public.patrol_occurrence_reservations%rowtype;
begin
    if not exists (
        select 1 from public.devices d
        where d.id = p_device_id and d.status = 'ACTIVE' and d.building_id is not null
    ) then
        raise exception 'DEVICE_NOT_ACTIVE';
    end if;

    if not exists (
        select 1 from public.guards g where g.id = p_guard_id and g.active
    ) then
        raise exception 'GUARD_NOT_ACTIVE';
    end if;

    select a.* into candidate
    from public.available_patrols_for_guard_device(p_device_id, p_guard_id) a
    where a.patrol_template_id = p_patrol_template_id
      and coalesce(a.execution_status, 'AVAILABLE') = 'AVAILABLE'
    order by a.scheduled_for
    limit 1;

    if not found then
        if exists (
            select 1
            from public.patrol_runs pr
            where pr.patrol_template_id = p_patrol_template_id
              and pr.guard_id <> p_guard_id
              and pr.status in ('IN_PROGRESS','COMPLETED','INCOMPLETE')
              and pr.scheduled_for >= now() - interval '24 hours'
        ) then
            raise exception 'PATROL_OCCURRENCE_ALREADY_EXECUTED';
        end if;
        raise exception 'PATROL_NOT_AVAILABLE';
    end if;

    perform pg_advisory_xact_lock(
        hashtextextended('patrol-reservation:' || candidate.schedule_window_id::text || ':' || extract(epoch from candidate.scheduled_for)::text, 0)
    );

    select * into current_reservation
    from public.patrol_occurrence_reservations r
    where r.schedule_window_id = candidate.schedule_window_id
      and r.scheduled_for = candidate.scheduled_for
    for update;

    if found then
        if current_reservation.consumed_at is not null then
            if current_reservation.guard_id = p_guard_id and current_reservation.device_id = p_device_id then
                return query select current_reservation.id, candidate.schedule_window_id, candidate.scheduled_for, candidate.available_until, candidate.patrol_name;
            end if;
            raise exception 'PATROL_RESERVED_BY_ANOTHER_GATEKEEPER';
        end if;

        if current_reservation.expires_at > now() then
            if current_reservation.guard_id = p_guard_id and current_reservation.device_id = p_device_id then
                return query select current_reservation.id, candidate.schedule_window_id, candidate.scheduled_for, candidate.available_until, candidate.patrol_name;
            end if;
            raise exception 'PATROL_RESERVED_BY_ANOTHER_GATEKEEPER';
        end if;

        update public.patrol_occurrence_reservations
        set guard_id = p_guard_id,
            device_id = p_device_id,
            expires_at = candidate.available_until,
            consumed_at = null,
            created_at = now()
        where id = current_reservation.id;

        return query select current_reservation.id, candidate.schedule_window_id, candidate.scheduled_for, candidate.available_until, candidate.patrol_name;
    end if;

    insert into public.patrol_occurrence_reservations(
        schedule_window_id, scheduled_for, guard_id, device_id, expires_at
    ) values (
        candidate.schedule_window_id, candidate.scheduled_for, p_guard_id, p_device_id, candidate.available_until
    )
    returning id into current_reservation.id;

    return query select current_reservation.id, candidate.schedule_window_id, candidate.scheduled_for, candidate.available_until, candidate.patrol_name;
end;
$$;

revoke all on function public.reserve_current_patrol_occurrence(uuid, uuid, uuid) from public, anon, authenticated;
grant execute on function public.reserve_current_patrol_occurrence(uuid, uuid, uuid) to service_role;

create or replace function public.enforce_patrol_occurrence_reservation()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
declare
    reservation_row public.patrol_occurrence_reservations%rowtype;
begin
    select * into reservation_row
    from public.patrol_occurrence_reservations r
    where r.schedule_window_id = new.schedule_window_id
      and r.scheduled_for = new.scheduled_for
    for update;

    if found then
        if reservation_row.guard_id <> new.guard_id or reservation_row.device_id <> new.device_id then
            raise exception 'PATROL_RESERVED_BY_ANOTHER_GATEKEEPER';
        end if;

        update public.patrol_occurrence_reservations
        set consumed_at = coalesce(consumed_at, now())
        where id = reservation_row.id;
    end if;

    return new;
end;
$$;

revoke all on function public.enforce_patrol_occurrence_reservation() from public, anon, authenticated;
grant execute on function public.enforce_patrol_occurrence_reservation() to service_role;

drop trigger if exists enforce_patrol_occurrence_reservation_before_insert on public.patrol_runs;
create trigger enforce_patrol_occurrence_reservation_before_insert
before insert on public.patrol_runs
for each row execute function public.enforce_patrol_occurrence_reservation();

commit;
