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
    v_property_id uuid;
    v_place_id uuid;
    v_checkpoint_id uuid;
    v_credential_id uuid;
    v_schedule_id uuid;
    v_raw_qr text;
    v_point_name text;
    v_point_index integer;
    v_slot integer;
    v_start_minute integer;
    v_start_time_value time;
    v_end_time_value time;
    v_checkpoint_ids uuid[] := array[]::uuid[];
begin
    if not exists (select 1 from public.organizations where id = p_organization_id and active) then
        raise exception using errcode = 'P0001', message = 'ORGANIZATION_NOT_FOUND';
    end if;

    select ln.id into v_property_id
    from public.location_nodes ln
    where ln.organization_id = p_organization_id
      and ln.kind = 'PROPERTY'
      and ln.archived_at is null
    order by ln.created_at
    limit 1;

    if v_property_id is null then
        insert into public.location_nodes(organization_id, parent_id, kind, name)
        values (p_organization_id, null, 'PROPERTY', 'Condomínio')
        returning id into v_property_id;
    end if;

    select ln.id into v_place_id
    from public.location_nodes ln
    where ln.organization_id = p_organization_id
      and ln.parent_id = v_property_id
      and ln.kind = 'PLACE'
      and ln.name = 'Pontos de ronda'
      and ln.archived_at is null
    limit 1;

    if v_place_id is null then
        insert into public.location_nodes(organization_id, parent_id, kind, name)
        values (p_organization_id, v_property_id, 'PLACE', 'Pontos de ronda')
        returning id into v_place_id;
    end if;

    for v_point_index in 1..15 loop
        v_point_name := case
            when v_point_index = 1 then 'Térreo'
            when v_point_index = 2 then 'Garagem'
            when v_point_index = 3 then 'Play'
            when v_point_index between 4 and 14 then (v_point_index - 3)::text || 'º andar'
            when v_point_index = 15 then 'Cobertura'
        end;

        select cp.id into v_checkpoint_id
        from public.checkpoints cp
        where cp.organization_id = p_organization_id
          and cp.system_key = 'FIXED_' || v_point_index
          and cp.archived_at is null
        limit 1;

        if v_checkpoint_id is null then
            insert into public.checkpoints(
                organization_id, location_node_id, name,
                minimum_travel_seconds_from_previous, fixed, system_key
            ) values (
                p_organization_id, v_place_id, v_point_name, 15, true, 'FIXED_' || v_point_index
            )
            returning id into v_checkpoint_id;
        end if;

        v_checkpoint_ids := array_append(v_checkpoint_ids, v_checkpoint_id);

        if not exists (
            select 1 from public.qr_credentials qc
            where qc.organization_id = p_organization_id
              and qc.checkpoint_id = v_checkpoint_id
              and qc.status = 'ACTIVE'
        ) then
            v_credential_id := gen_random_uuid();
            v_raw_qr := 'porteirinho:v1:' || v_credential_id::text || ':' || gen_random_uuid()::text;
            insert into public.qr_credentials(
                id, organization_id, checkpoint_id, token_hash, version, status, issued_at
            ) values (
                v_credential_id,
                p_organization_id,
                v_checkpoint_id,
                encode(digest(v_raw_qr, 'sha256'), 'hex'),
                1,
                'ACTIVE',
                now()
            );
            insert into private.qr_payloads(qr_credential_id, organization_id, raw_payload)
            values (v_credential_id, p_organization_id, v_raw_qr);
        end if;
    end loop;

    for v_slot in 1..4 loop
        select ps.id into v_schedule_id
        from public.patrol_schedules ps
        where ps.organization_id = p_organization_id
          and ps.fixed_slot = v_slot
          and ps.archived_at is null
        limit 1;

        if v_schedule_id is null then
            v_start_minute := (v_slot - 1) * 360;
            v_start_time_value := make_time(v_start_minute / 60, v_start_minute % 60, 0);
            v_end_time_value := make_time(((v_start_minute + 60) % 1440) / 60, ((v_start_minute + 60) % 1440) % 60, 0);

            insert into public.patrol_schedules(
                organization_id, property_id, name, weekdays,
                start_time, end_time, tolerance_minutes, fixed_slot,
                start_tolerance_minutes, end_tolerance_minutes, target_duration_minutes
            ) values (
                p_organization_id,
                v_property_id,
                'Ronda ' || v_slot,
                array[1,2,3,4,5,6,7]::smallint[],
                v_start_time_value,
                v_end_time_value,
                10,
                v_slot,
                10,
                10,
                60
            )
            returning id into v_schedule_id;
        end if;

        for v_point_index in 1..array_length(v_checkpoint_ids, 1) loop
            insert into public.schedule_checkpoints(
                organization_id, schedule_id, checkpoint_id, sequence
            ) values (
                p_organization_id,
                v_schedule_id,
                v_checkpoint_ids[v_point_index],
                v_point_index
            )
            on conflict (schedule_id, checkpoint_id) do update
            set sequence = excluded.sequence;
        end loop;
    end loop;

    return jsonb_build_object(
        'organization_id', p_organization_id,
        'fixed_checkpoints', array_length(v_checkpoint_ids, 1),
        'fixed_patrols', 4
    );
end;
$$;

revoke all on function public.bootstrap_fixed_patrols(uuid) from public, anon, authenticated;
grant execute on function public.bootstrap_fixed_patrols(uuid) to service_role;

commit;
