begin;

create or replace function public.admin_create_extra_checkpoint(
    p_organization_id uuid,
    p_name text
)
returns jsonb
language plpgsql
security invoker
set search_path = public
as $$
declare
    place_id uuid;
    checkpoint_id uuid := gen_random_uuid();
    schedule_row record;
    next_sequence integer;
    normalized_name text := btrim(p_name);
begin
    if char_length(normalized_name) < 2 or char_length(normalized_name) > 100 then
        raise exception using errcode = 'P0001', message = 'INVALID_CHECKPOINT_NAME';
    end if;

    if exists (
        select 1
        from public.patrol_executions
        where organization_id = p_organization_id
          and status = 'IN_PROGRESS'
    ) then
        raise exception using errcode = 'P0001', message = 'PATROL_PLAN_ACTIVE';
    end if;

    select id into place_id
    from public.location_nodes
    where organization_id = p_organization_id
      and kind = 'PLACE'
      and active
      and archived_at is null
    order by created_at
    limit 1;

    if place_id is null then
        raise exception using errcode = 'P0001', message = 'PLACE_NOT_FOUND';
    end if;

    insert into public.checkpoints(
        id, organization_id, location_node_id, name,
        minimum_travel_seconds_from_previous, fixed, active
    ) values (
        checkpoint_id, p_organization_id, place_id, normalized_name,
        15, false, true
    );

    for schedule_row in
        select id
        from public.patrol_schedules
        where organization_id = p_organization_id
          and fixed_slot between 1 and 4
          and active
          and archived_at is null
        order by fixed_slot
    loop
        select coalesce(max(sequence), 0) + 1 into next_sequence
        from public.schedule_checkpoints
        where schedule_id = schedule_row.id;

        insert into public.schedule_checkpoints(
            organization_id, schedule_id, checkpoint_id, sequence
        ) values (
            p_organization_id, schedule_row.id, checkpoint_id, next_sequence
        );
    end loop;

    return jsonb_build_object(
        'created', true,
        'checkpoint_id', checkpoint_id,
        'name', normalized_name
    );
end;
$$;

revoke all on function public.admin_create_extra_checkpoint(uuid, text) from public, anon, authenticated;
grant execute on function public.admin_create_extra_checkpoint(uuid, text) to service_role;

commit;
