begin;

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
        organization_id, schedule_id, scheduled_window_start, user_id, device_id
    ) values (
        p_organization_id, p_schedule_id, window_start, p_user_id, p_device_id
    )
    on conflict (schedule_id, scheduled_window_start) do nothing;

    select * into existing
    from public.patrol_reservations
    where schedule_id = p_schedule_id
      and scheduled_window_start = window_start;

    if existing.user_id <> p_user_id or existing.device_id <> p_device_id then
        return jsonb_build_object(
            'reserved', false,
            'conflict', true,
            'message', 'Esta ronda já foi iniciada ou concluída por outro porteiro.',
            'scheduled_window_start_ms', floor(extract(epoch from window_start) * 1000)::bigint,
            'reserved_by_user_id', existing.user_id
        );
    end if;

    return jsonb_build_object(
        'reserved', true,
        'conflict', false,
        'scheduled_window_start_ms', floor(extract(epoch from window_start) * 1000)::bigint,
        'scheduled_window_end_ms', floor(extract(epoch from window_end) * 1000)::bigint
    );
end;
$$;

revoke all on function public.reserve_current_patrol(uuid, uuid, uuid, uuid) from public, anon, authenticated;
grant execute on function public.reserve_current_patrol(uuid, uuid, uuid, uuid) to service_role;

commit;
