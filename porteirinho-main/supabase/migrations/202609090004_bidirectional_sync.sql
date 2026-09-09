begin;

alter table public.organizations
    add column if not exists timezone text not null default 'America/Sao_Paulo';

alter table public.devices
    add column if not exists can_admin boolean not null default false;

alter table public.app_users
    add column if not exists pin_salt_base64 text,
    add column if not exists pin_hash_base64 text;

alter table public.shifts
    add column if not exists updated_at timestamptz not null default now();

alter table public.patrol_executions
    add column if not exists updated_at timestamptz not null default now();

alter table public.alerts
    add column if not exists updated_at timestamptz not null default now();

create or replace function public.touch_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

revoke all on function public.touch_updated_at() from public, anon, authenticated;
grant execute on function public.touch_updated_at() to service_role;

drop trigger if exists shifts_touch_updated_at on public.shifts;
create trigger shifts_touch_updated_at
before update on public.shifts
for each row execute function public.touch_updated_at();

drop trigger if exists patrol_executions_touch_updated_at on public.patrol_executions;
create trigger patrol_executions_touch_updated_at
before update on public.patrol_executions
for each row execute function public.touch_updated_at();

drop trigger if exists alerts_touch_updated_at on public.alerts;
create trigger alerts_touch_updated_at
before update on public.alerts
for each row execute function public.touch_updated_at();

create or replace function public.materialize_ingested_event(p_event_id uuid)
returns jsonb
language plpgsql
security invoker
set search_path = public
as $$
declare
    e public.ingested_events%rowtype;
    p jsonb;
    expected_count integer;
    visited_count integer;
    reservation_owner uuid;
    reservation_device uuid;
    next_sequence integer;
    schedule_row public.patrol_schedules%rowtype;
begin
    select * into e
    from public.ingested_events
    where event_id = p_event_id
    for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'EVENT_NOT_FOUND';
    end if;

    if e.processing_status = 'PROCESSED' then
        return jsonb_build_object('status', 'PROCESSED');
    end if;

    p := e.payload;

    begin
        case e.event_type
            when 'SHIFT_STARTED' then
                insert into public.shifts(
                    id, organization_id, user_id, device_id, started_at_device, updated_at
                ) values (
                    (p->>'shift_id')::uuid,
                    e.organization_id,
                    (p->>'user_id')::uuid,
                    e.device_id,
                    to_timestamp((p->>'started_at')::double precision / 1000.0),
                    now()
                )
                on conflict (id) do nothing;

            when 'SHIFT_ENDED' then
                update public.shifts
                set ended_at_device = to_timestamp((p->>'ended_at')::double precision / 1000.0),
                    ended_at_server = now()
                where id = (p->>'shift_id')::uuid
                  and organization_id = e.organization_id;

            when 'PATROL_STARTED' then
                select user_id, device_id
                into reservation_owner, reservation_device
                from public.patrol_reservations
                where organization_id = e.organization_id
                  and schedule_id = (p->>'schedule_id')::uuid
                  and scheduled_window_start = to_timestamp((p->>'scheduled_window_start')::double precision / 1000.0);

                if reservation_owner is null then
                    raise exception using errcode = 'P0001', message = 'PATROL_NOT_RESERVED';
                end if;
                if reservation_owner <> (p->>'user_id')::uuid or reservation_device <> e.device_id then
                    raise exception using errcode = 'P0001', message = 'PATROL_RESERVED_BY_ANOTHER_GATEKEEPER';
                end if;

                insert into public.patrol_executions(
                    id, organization_id, schedule_id, shift_id, user_id, device_id,
                    scheduled_window_start, scheduled_window_end,
                    started_at_device, status, suspicious, updated_at
                ) values (
                    (p->>'execution_id')::uuid,
                    e.organization_id,
                    (p->>'schedule_id')::uuid,
                    (p->>'shift_id')::uuid,
                    (p->>'user_id')::uuid,
                    e.device_id,
                    to_timestamp((p->>'scheduled_window_start')::double precision / 1000.0),
                    to_timestamp((p->>'scheduled_window_end')::double precision / 1000.0),
                    to_timestamp((p->>'started_at')::double precision / 1000.0),
                    'IN_PROGRESS',
                    false,
                    now()
                )
                on conflict (id) do nothing;

            when 'CHECKPOINT_VISITED' then
                insert into public.checkpoint_visits(
                    id, organization_id, execution_id, checkpoint_id, qr_credential_id,
                    scanned_at_device, suspicious, suspicion_reason
                ) values (
                    (p->>'visit_id')::uuid,
                    e.organization_id,
                    (p->>'execution_id')::uuid,
                    (p->>'checkpoint_id')::uuid,
                    (p->>'qr_credential_id')::uuid,
                    to_timestamp((p->>'scanned_at')::double precision / 1000.0),
                    coalesce((p->>'suspicious')::boolean, false),
                    nullif(p->>'suspicion_reason', '')
                )
                on conflict (execution_id, checkpoint_id) do nothing;

                update public.patrol_executions
                set suspicious = suspicious or coalesce((p->>'suspicious')::boolean, false)
                where id = (p->>'execution_id')::uuid
                  and organization_id = e.organization_id;

            when 'PATROL_FINISHED' then
                select count(*) into expected_count
                from public.schedule_checkpoints
                where schedule_id = (
                    select schedule_id
                    from public.patrol_executions
                    where id = (p->>'execution_id')::uuid
                      and organization_id = e.organization_id
                );

                select count(*) into visited_count
                from public.checkpoint_visits
                where execution_id = (p->>'execution_id')::uuid
                  and organization_id = e.organization_id;

                if expected_count = 0 or visited_count < expected_count then
                    raise exception using errcode = 'P0001', message = 'PATROL_INCOMPLETE';
                end if;

                update public.patrol_executions
                set ended_at_device = to_timestamp((p->>'ended_at')::double precision / 1000.0),
                    ended_at_server = now(),
                    status = 'COMPLETED'
                where id = (p->>'execution_id')::uuid
                  and organization_id = e.organization_id;

            when 'OCCURRENCE_RECORDED' then
                insert into public.occurrences(
                    id, organization_id, execution_id, checkpoint_id, category,
                    description, created_at_device
                ) values (
                    (p->>'occurrence_id')::uuid,
                    e.organization_id,
                    (p->>'execution_id')::uuid,
                    nullif(p->>'checkpoint_id', '')::uuid,
                    coalesce(nullif(p->>'category', ''), 'OBSERVATION'),
                    p->>'description',
                    to_timestamp((p->>'created_at')::double precision / 1000.0)
                )
                on conflict (id) do nothing;

            when 'ALERT_CREATED' then
                insert into public.alerts(
                    id, organization_id, type, description, user_id, execution_id, device_id,
                    created_at_device, updated_at
                ) values (
                    (p->>'alert_id')::uuid,
                    e.organization_id,
                    p->>'type',
                    p->>'description',
                    nullif(p->>'user_id', '')::uuid,
                    nullif(p->>'execution_id', '')::uuid,
                    e.device_id,
                    to_timestamp((p->>'created_at')::double precision / 1000.0),
                    now()
                )
                on conflict (id) do nothing;

            when 'GATEKEEPER_CREATED' then
                if not exists (
                    select 1 from public.devices
                    where id = e.device_id and organization_id = e.organization_id and can_admin
                ) then
                    raise exception using errcode = 'P0001', message = 'ADMIN_DEVICE_REQUIRED';
                end if;

                insert into public.app_users(
                    id, organization_id, display_name, role, pin_salt_base64, pin_hash_base64,
                    must_change_pin, pin_issued_at, active, updated_at
                ) values (
                    (p->>'user_id')::uuid,
                    e.organization_id,
                    p->>'display_name',
                    'GATEKEEPER',
                    p->>'pin_salt_base64',
                    p->>'pin_hash_base64',
                    true,
                    to_timestamp((p->>'pin_issued_at')::double precision / 1000.0),
                    true,
                    now()
                )
                on conflict (id) do update set
                    display_name = excluded.display_name,
                    pin_salt_base64 = excluded.pin_salt_base64,
                    pin_hash_base64 = excluded.pin_hash_base64,
                    must_change_pin = true,
                    pin_issued_at = excluded.pin_issued_at,
                    active = true,
                    archived_at = null,
                    updated_at = now();

            when 'PIN_CHANGED' then
                update public.app_users
                set pin_salt_base64 = p->>'pin_salt_base64',
                    pin_hash_base64 = p->>'pin_hash_base64',
                    must_change_pin = false,
                    pin_changed_at = to_timestamp((p->>'changed_at')::double precision / 1000.0),
                    updated_at = now()
                where id = (p->>'user_id')::uuid
                  and organization_id = e.organization_id;

            when 'SCHEDULE_UPDATED' then
                if not exists (
                    select 1 from public.devices
                    where id = e.device_id and organization_id = e.organization_id and can_admin
                ) then
                    raise exception using errcode = 'P0001', message = 'ADMIN_DEVICE_REQUIRED';
                end if;

                select * into schedule_row
                from public.patrol_schedules
                where id = (p->>'schedule_id')::uuid
                  and organization_id = e.organization_id;

                if not found or schedule_row.fixed_slot not between 1 and 4 then
                    raise exception using errcode = 'P0001', message = 'INVALID_FIXED_PATROL';
                end if;

                update public.patrol_schedules
                set name = p->>'name',
                    start_time = make_time(((p->>'start_minute')::integer / 60), ((p->>'start_minute')::integer % 60), 0),
                    end_time = make_time(((p->>'end_minute')::integer / 60), ((p->>'end_minute')::integer % 60), 0),
                    start_tolerance_minutes = (p->>'start_tolerance_minutes')::integer,
                    end_tolerance_minutes = (p->>'end_tolerance_minutes')::integer,
                    target_duration_minutes = (p->>'target_duration_minutes')::integer,
                    updated_at = now()
                where id = schedule_row.id;

            when 'CHECKPOINT_CREATED' then
                if not exists (
                    select 1 from public.devices
                    where id = e.device_id and organization_id = e.organization_id and can_admin
                ) then
                    raise exception using errcode = 'P0001', message = 'ADMIN_DEVICE_REQUIRED';
                end if;

                insert into public.checkpoints(
                    id, organization_id, location_node_id, name, description,
                    minimum_travel_seconds_from_previous, fixed, system_key, active, updated_at
                ) values (
                    (p->>'checkpoint_id')::uuid,
                    e.organization_id,
                    (p->>'location_node_id')::uuid,
                    p->>'name',
                    nullif(p->>'description', ''),
                    15,
                    false,
                    null,
                    true,
                    now()
                )
                on conflict (id) do update set
                    name = excluded.name,
                    description = excluded.description,
                    active = true,
                    archived_at = null,
                    updated_at = now();

                select coalesce(max(sequence), 0) + 1 into next_sequence
                from public.schedule_checkpoints sc
                join public.patrol_schedules ps on ps.id = sc.schedule_id
                where ps.organization_id = e.organization_id
                  and ps.fixed_slot between 1 and 4
                  and ps.archived_at is null;

                insert into public.schedule_checkpoints(organization_id, schedule_id, checkpoint_id, sequence)
                select e.organization_id, ps.id, (p->>'checkpoint_id')::uuid,
                       coalesce((select max(sc.sequence) + 1 from public.schedule_checkpoints sc where sc.schedule_id = ps.id), 1)
                from public.patrol_schedules ps
                where ps.organization_id = e.organization_id
                  and ps.fixed_slot between 1 and 4
                  and ps.archived_at is null
                on conflict (schedule_id, checkpoint_id) do nothing;

            when 'QR_REPLACED' then
                if not exists (
                    select 1 from public.devices
                    where id = e.device_id and organization_id = e.organization_id and can_admin
                ) then
                    raise exception using errcode = 'P0001', message = 'ADMIN_DEVICE_REQUIRED';
                end if;

                update public.qr_credentials
                set status = 'REVOKED', revoked_at = now()
                where organization_id = e.organization_id
                  and checkpoint_id = (p->>'checkpoint_id')::uuid
                  and status = 'ACTIVE';

                insert into public.qr_credentials(
                    id, organization_id, checkpoint_id, token_hash, version, status, issued_at
                ) values (
                    (p->>'qr_credential_id')::uuid,
                    e.organization_id,
                    (p->>'checkpoint_id')::uuid,
                    p->>'token_hash',
                    (p->>'version')::integer,
                    'ACTIVE',
                    to_timestamp((p->>'issued_at')::double precision / 1000.0)
                )
                on conflict (id) do nothing;

            else
                raise exception using errcode = 'P0001', message = 'UNSUPPORTED_EVENT_TYPE';
        end case;

        update public.ingested_events
        set processing_status = 'PROCESSED', processing_error = null
        where event_id = p_event_id;

        return jsonb_build_object('status', 'PROCESSED');

    exception
        when sqlstate 'P0001' then
            update public.ingested_events
            set processing_status = 'REJECTED', processing_error = sqlerrm
            where event_id = p_event_id;
            return jsonb_build_object('status', 'REJECTED', 'error', sqlerrm);
    end;
end;
$$;

revoke all on function public.materialize_ingested_event(uuid) from public, anon, authenticated;
grant execute on function public.materialize_ingested_event(uuid) to service_role;

commit;
