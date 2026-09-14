begin;

create or replace function public.create_extra_checkpoint_with_qr(
    p_name text,
    p_actor_id uuid,
    p_token_value text,
    p_token_hash text,
    p_fingerprint text
)
returns table(checkpoint_id uuid, qr_token_id uuid, qr_version integer)
language plpgsql
security invoker
set search_path = public
as $$
declare
    v_building_id uuid;
    v_floor_id uuid;
    v_checkpoint_id uuid;
    v_qr record;
    v_sort_order integer;
begin
    if nullif(btrim(p_name), '') is null then
        raise exception 'CHECKPOINT_NAME_REQUIRED';
    end if;

    select b.id into v_building_id
    from public.buildings b
    where b.active
      and b.archived_at is null
      and b.e2e_run_id is null
    order by b.operations_started_at desc nulls last, b.created_at
    limit 1;

    if v_building_id is null then
        raise exception 'ACTIVE_BUILDING_NOT_FOUND';
    end if;

    if exists (
        select 1
        from public.patrol_runs pr
        join public.patrol_templates pt on pt.id = pr.patrol_template_id
        where pt.building_id = v_building_id
          and pr.status = 'IN_PROGRESS'
    ) then
        raise exception 'PATROL_ACTIVE';
    end if;

    select f.id into v_floor_id
    from public.floors f
    join public.blocks bl on bl.id = f.block_id
    where bl.building_id = v_building_id
      and bl.active and bl.archived_at is null
      and f.active and f.archived_at is null
      and f.system_fixed
    order by bl.sort_order, f.sort_order, f.created_at
    limit 1;

    if v_floor_id is null then
        raise exception 'DEFAULT_FLOOR_NOT_FOUND';
    end if;

    select coalesce(max(c.sort_order), 0) + 1 into v_sort_order
    from public.checkpoints c
    where c.floor_id = v_floor_id;

    insert into public.checkpoints(
        floor_id, name, sort_order, active, created_by, system_fixed
    ) values (
        v_floor_id, btrim(p_name), v_sort_order, true, p_actor_id, false
    ) returning id into v_checkpoint_id;

    select * into v_qr
    from public.issue_qr_token(
        v_checkpoint_id,
        p_actor_id,
        p_token_value,
        p_token_hash,
        p_fingerprint,
        false
    );

    insert into public.audit_logs(
        actor_type, actor_admin_id, action, entity_type, entity_id, metadata
    ) values (
        'ADMIN', p_actor_id, 'EXTRA_CHECKPOINT_CREATED', 'checkpoint', v_checkpoint_id::text,
        jsonb_build_object('name', btrim(p_name), 'floor_id', v_floor_id)
    );

    return query select v_checkpoint_id, v_qr.qr_token_id, v_qr.version;
end;
$$;

revoke all on function public.create_extra_checkpoint_with_qr(text, uuid, text, text, text) from public, anon, authenticated;
grant execute on function public.create_extra_checkpoint_with_qr(text, uuid, text, text, text) to service_role;

commit;
