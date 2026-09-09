begin;

create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

do $$
begin
    if not exists (
        select 1 from information_schema.tables
        where table_schema = 'private' and table_name = 'qr_payloads'
    ) then
        create table private.qr_payloads (
            qr_credential_id uuid primary key references public.qr_credentials(id) on delete restrict,
            organization_id uuid not null references public.organizations(id) on delete restrict,
            raw_payload text not null,
            created_at timestamptz not null default now()
        );
        create index qr_payloads_org_idx on private.qr_payloads(organization_id);
    end if;
end;
$$;

revoke all on private.qr_payloads from public, anon, authenticated;
grant select, insert, update on private.qr_payloads to service_role;

create or replace function public.bootstrap_fixed_patrols(p_organization_id uuid)
returns jsonb
language plpgsql
security invoker
set search_path = public, private
as $$
declare
    property_id uuid;
    place_id uuid;
    checkpoint_id uuid;
    credential_id uuid;
    schedule_id uuid;
    raw_qr text;
    point_name text;
    point_index integer;
    slot integer;
    start_minute integer;
    start_time_value time;
    end_time_value time;
    checkpoint_ids uuid[] := array[]::uuid[];
begin
    if not exists (select 1 from public.organizations where id = p_organization_id and active) then
        raise exception using errcode = 'P0001', message = 'ORGANIZATION_NOT_FOUND';
    end if;

    select id into property_id
    from public.location_nodes
    where organization_id = p_organization_id
      and kind = 'PROPERTY'
      and archived_at is null
    order by created_at
    limit 1;

    if property_id is null then
        insert into public.location_nodes(organization_id, parent_id, kind, name)
        values (p_organization_id, null, 'PROPERTY', 'Condomínio')
        returning id into property_id;
    end if;

    select id into place_id
    from public.location_nodes
    where organization_id = p_organization_id
      and parent_id = property_id
      and kind = 'PLACE'
      and name = 'Pontos de ronda'
      and archived_at is null
    limit 1;

    if place_id is null then
        insert into public.location_nodes(organization_id, parent_id, kind, name)
        values (p_organization_id, property_id, 'PLACE', 'Pontos de ronda')
        returning id into place_id;
    end if;

    for point_index in 1..15 loop
        point_name := case
            when point_index = 1 then 'Térreo'
            when point_index = 2 then 'Garagem'
            when point_index = 3 then 'Play'
            when point_index between 4 and 14 then (point_index - 3)::text || 'º andar'
            when point_index = 15 then 'Cobertura'
        end;

        select id into checkpoint_id
        from public.checkpoints
        where organization_id = p_organization_id
          and system_key = 'FIXED_' || point_index
          and archived_at is null
        limit 1;

        if checkpoint_id is null then
            insert into public.checkpoints(
                organization_id, location_node_id, name,
                minimum_travel_seconds_from_previous, fixed, system_key
            ) values (
                p_organization_id, place_id, point_name, 15, true, 'FIXED_' || point_index
            )
            returning id into checkpoint_id;
        end if;

        checkpoint_ids := array_append(checkpoint_ids, checkpoint_id);

        if not exists (
            select 1 from public.qr_credentials
            where organization_id = p_organization_id
              and checkpoint_id = checkpoint_id
              and status = 'ACTIVE'
        ) then
            credential_id := gen_random_uuid();
            raw_qr := 'porteirinho:v1:' || credential_id::text || ':' || gen_random_uuid()::text;
            insert into public.qr_credentials(
                id, organization_id, checkpoint_id, token_hash, version, status, issued_at
            ) values (
                credential_id,
                p_organization_id,
                checkpoint_id,
                encode(digest(raw_qr, 'sha256'), 'hex'),
                1,
                'ACTIVE',
                now()
            );
            insert into private.qr_payloads(qr_credential_id, organization_id, raw_payload)
            values (credential_id, p_organization_id, raw_qr);
        end if;
    end loop;

    for slot in 1..4 loop
        select id into schedule_id
        from public.patrol_schedules
        where organization_id = p_organization_id
          and fixed_slot = slot
          and archived_at is null
        limit 1;

        if schedule_id is null then
            start_minute := (slot - 1) * 360;
            start_time_value := make_time(start_minute / 60, start_minute % 60, 0);
            end_time_value := make_time(((start_minute + 60) % 1440) / 60, ((start_minute + 60) % 1440) % 60, 0);

            insert into public.patrol_schedules(
                organization_id, property_id, name, weekdays,
                start_time, end_time, tolerance_minutes, fixed_slot,
                start_tolerance_minutes, end_tolerance_minutes, target_duration_minutes
            ) values (
                p_organization_id,
                property_id,
                'Ronda ' || slot,
                array[1,2,3,4,5,6,7]::smallint[],
                start_time_value,
                end_time_value,
                10,
                slot,
                10,
                10,
                60
            )
            returning id into schedule_id;
        end if;

        for point_index in 1..array_length(checkpoint_ids, 1) loop
            insert into public.schedule_checkpoints(
                organization_id, schedule_id, checkpoint_id, sequence
            ) values (
                p_organization_id,
                schedule_id,
                checkpoint_ids[point_index],
                point_index
            )
            on conflict (schedule_id, checkpoint_id) do update
            set sequence = excluded.sequence;
        end loop;
    end loop;

    return jsonb_build_object(
        'organization_id', p_organization_id,
        'fixed_checkpoints', array_length(checkpoint_ids, 1),
        'fixed_patrols', 4
    );
end;
$$;

revoke all on function public.bootstrap_fixed_patrols(uuid) from public, anon, authenticated;
grant execute on function public.bootstrap_fixed_patrols(uuid) to service_role;

commit;
