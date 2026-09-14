begin;

alter table public.patrol_templates
    add column if not exists fixed_slot smallint;

-- Preserve current templates but give them stable slots. The current production
-- setup has 3 fixed patrols beginning at 06:00, 14:00 and 22:00, so those map
-- to slots 2, 3 and 4 and slot 1 is created at 00:00.
with per_template as (
    select
        pt.id,
        pt.building_id,
        pt.created_at,
        min(w.start_time) filter (where w.active) as start_time
    from public.patrol_templates pt
    left join public.patrol_schedule_windows w on w.patrol_template_id = pt.id
    where pt.active
      and pt.system_fixed
      and pt.archived_at is null
    group by pt.id, pt.building_id, pt.created_at
), ranked as (
    select
        p.*,
        count(*) over (partition by p.building_id) as template_count,
        min(p.start_time) over (partition by p.building_id) as building_first_start,
        row_number() over (
            partition by p.building_id
            order by p.start_time nulls last, p.created_at, p.id
        ) as rn
    from per_template p
)
update public.patrol_templates pt
set fixed_slot = case
    when r.template_count = 3
         and r.building_first_start >= time '04:00:00'
      then (r.rn + 1)::smallint
    else r.rn::smallint
end
from ranked r
where pt.id = r.id
  and pt.fixed_slot is null
  and r.rn <= 4;

create or replace function public.ensure_four_fixed_patrols(p_building_id uuid)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
    v_building public.buildings%rowtype;
    v_slot smallint;
    v_template_id uuid;
    v_start time;
    v_end time;
begin
    select * into v_building
    from public.buildings
    where id = p_building_id
      and active
      and archived_at is null;

    if not found then
        raise exception 'BUILDING_NOT_ACTIVE';
    end if;

    -- E2E fixtures keep their own deliberately small setup.
    if v_building.e2e_run_id is not null then
        return;
    end if;

    for v_slot in 1..4 loop
        v_start := case v_slot
            when 1 then time '00:00:00'
            when 2 then time '06:00:00'
            when 3 then time '12:00:00'
            else time '18:00:00'
        end;
        v_end := (v_start + interval '1 hour')::time;

        select id into v_template_id
        from public.patrol_templates
        where building_id = p_building_id
          and active
          and archived_at is null
          and system_fixed
          and fixed_slot = v_slot
        limit 1;

        if v_template_id is null then
            insert into public.patrol_templates(
                building_id,
                name,
                description,
                active,
                system_fixed,
                fixed_slot
            ) values (
                p_building_id,
                'Ronda ' || v_slot,
                'Ronda fixa ' || v_slot,
                true,
                true,
                v_slot
            )
            returning id into v_template_id;
        end if;

        -- The four fixed patrols use a one-hour operational window. The ADM
        -- edits only the start time; duration remains backend policy.
        update public.patrol_schedule_windows
        set start_time = v_start,
            end_time = v_end
        where patrol_template_id = v_template_id
          and active;

        insert into public.patrol_schedule_windows(
            patrol_template_id,
            day_of_week,
            start_time,
            end_time,
            late_tolerance_minutes,
            active
        )
        select
            v_template_id,
            d::smallint,
            v_start,
            v_end,
            15,
            true
        from generate_series(1, 7) as d
        where not exists (
            select 1
            from public.patrol_schedule_windows w
            where w.patrol_template_id = v_template_id
              and w.day_of_week = d
              and w.active
        );

        update public.patrol_template_checkpoints ptc
        set active = true,
            required = true,
            archived_at = null,
            archived_by = null
        from public.checkpoints cp
        join public.floors f on f.id = cp.floor_id
        join public.blocks bl on bl.id = f.block_id
        where ptc.patrol_template_id = v_template_id
          and ptc.checkpoint_id = cp.id
          and cp.active
          and cp.archived_at is null
          and f.active
          and f.archived_at is null
          and bl.active
          and bl.archived_at is null
          and bl.building_id = p_building_id;

        insert into public.patrol_template_checkpoints(
            patrol_template_id,
            checkpoint_id,
            required,
            active
        )
        select
            v_template_id,
            cp.id,
            true,
            true
        from public.checkpoints cp
        join public.floors f on f.id = cp.floor_id
        join public.blocks bl on bl.id = f.block_id
        where cp.active
          and cp.archived_at is null
          and f.active
          and f.archived_at is null
          and bl.active
          and bl.archived_at is null
          and bl.building_id = p_building_id
          and not exists (
              select 1
              from public.patrol_template_checkpoints existing
              where existing.patrol_template_id = v_template_id
                and existing.checkpoint_id = cp.id
          );
    end loop;
end;
$$;

revoke all on function public.ensure_four_fixed_patrols(uuid) from public, anon, authenticated;
grant execute on function public.ensure_four_fixed_patrols(uuid) to service_role;

do $$
declare
    b record;
begin
    for b in
        select id
        from public.buildings
        where active
          and archived_at is null
          and e2e_run_id is null
    loop
        perform public.ensure_four_fixed_patrols(b.id);
    end loop;
end;
$$;

do $$
begin
    if not exists (
        select 1
        from pg_constraint
        where conname = 'patrol_templates_fixed_slot_check'
          and conrelid = 'public.patrol_templates'::regclass
    ) then
        alter table public.patrol_templates
            add constraint patrol_templates_fixed_slot_check
            check (
                not (system_fixed and active and archived_at is null)
                or (fixed_slot is not null and fixed_slot between 1 and 4)
            );
    end if;
end;
$$;

create unique index if not exists one_fixed_patrol_slot_per_building_idx
    on public.patrol_templates(building_id, fixed_slot)
    where active and archived_at is null and system_fixed;

create or replace function public.protect_four_fixed_patrols()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
declare
    v_e2e_run_id text;
begin
    select e2e_run_id into v_e2e_run_id
    from public.buildings
    where id = coalesce(new.building_id, old.building_id);

    if v_e2e_run_id is not null then
        return case when tg_op = 'DELETE' then old else new end;
    end if;

    if tg_op = 'DELETE' then
        if old.active and old.archived_at is null and old.system_fixed and old.fixed_slot between 1 and 4 then
            raise exception 'FOUR_FIXED_PATROLS_CANNOT_BE_DELETED';
        end if;
        return old;
    end if;

    if new.active and new.archived_at is null then
        if not new.system_fixed or new.fixed_slot is null or new.fixed_slot not between 1 and 4 then
            raise exception 'ONLY_FOUR_FIXED_PATROLS_ALLOWED';
        end if;
    end if;

    if tg_op = 'UPDATE'
       and old.active
       and old.archived_at is null
       and old.system_fixed
       and old.fixed_slot between 1 and 4
       and (
           new.building_id is distinct from old.building_id
           or new.system_fixed is distinct from true
           or new.fixed_slot is distinct from old.fixed_slot
           or new.active is distinct from true
           or new.archived_at is not null
       ) then
        raise exception 'FOUR_FIXED_PATROLS_ARE_IMMUTABLE';
    end if;

    return new;
end;
$$;

revoke all on function public.protect_four_fixed_patrols() from public, anon, authenticated;
grant execute on function public.protect_four_fixed_patrols() to service_role;

drop trigger if exists protect_four_fixed_patrols_trigger on public.patrol_templates;
create trigger protect_four_fixed_patrols_trigger
before insert or update or delete on public.patrol_templates
for each row execute function public.protect_four_fixed_patrols();

create or replace function public.protect_fixed_patrol_windows()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
declare
    v_protected boolean;
begin
    select exists(
        select 1
        from public.patrol_templates pt
        join public.buildings b on b.id = pt.building_id
        where pt.id = coalesce(new.patrol_template_id, old.patrol_template_id)
          and pt.active
          and pt.archived_at is null
          and pt.system_fixed
          and pt.fixed_slot between 1 and 4
          and b.e2e_run_id is null
    ) into v_protected;

    if not v_protected then
        return case when tg_op = 'DELETE' then old else new end;
    end if;

    if tg_op = 'DELETE' then
        raise exception 'FIXED_PATROL_WINDOW_CANNOT_BE_DELETED';
    end if;

    if old.active and (
        new.active is distinct from true
        or new.patrol_template_id is distinct from old.patrol_template_id
        or new.archived_at is not null
    ) then
        raise exception 'FIXED_PATROL_WINDOW_CANNOT_BE_DISABLED';
    end if;

    return new;
end;
$$;

revoke all on function public.protect_fixed_patrol_windows() from public, anon, authenticated;
grant execute on function public.protect_fixed_patrol_windows() to service_role;

drop trigger if exists protect_fixed_patrol_windows_trigger on public.patrol_schedule_windows;
create trigger protect_fixed_patrol_windows_trigger
before update or delete on public.patrol_schedule_windows
for each row execute function public.protect_fixed_patrol_windows();

-- Keep the existing authenticated-admin RPC signature, but enforce the fixed
-- slot and the backend-owned 60-minute duration.
create or replace function public.admin_update_fixed_patrol_times(
    p_template_id uuid,
    p_start_time time without time zone,
    p_end_time time without time zone,
    p_expected_versions jsonb
)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
declare
    v_count integer;
    v_expected_end time;
begin
    if auth.uid() is null
       or coalesce(auth.jwt()->'app_metadata'->>'role','') <> 'admin' then
        raise exception 'ADMIN_REQUIRED';
    end if;

    if p_start_time is null then
        raise exception 'INVALID_SCHEDULE_TIME';
    end if;

    v_expected_end := (p_start_time + interval '1 hour')::time;
    if p_end_time is distinct from v_expected_end then
        raise exception 'PATROL_DURATION_IS_BACKEND_CONTROLLED';
    end if;

    if p_expected_versions is null or jsonb_typeof(p_expected_versions) <> 'object' then
        raise exception 'CONFLICT_VERSION_MISMATCH';
    end if;

    perform 1
    from public.patrol_templates t
    where t.id = p_template_id
      and t.active
      and t.archived_at is null
      and t.system_fixed
      and t.fixed_slot between 1 and 4
    for update;

    if not found then
        raise exception 'FIXED_PATROL_NOT_FOUND';
    end if;

    perform 1
    from public.patrol_schedule_windows w
    where w.patrol_template_id = p_template_id
      and w.active
    order by w.id
    for update;

    select count(*) into v_count
    from public.patrol_schedule_windows w
    where w.patrol_template_id = p_template_id
      and w.active;

    if v_count = 0 then
        raise exception 'FIXED_PATROL_HAS_NO_WINDOWS';
    end if;

    if v_count <> (select count(*) from jsonb_object_keys(p_expected_versions))
       or exists (
           select 1
           from public.patrol_schedule_windows w
           where w.patrol_template_id = p_template_id
             and w.active
             and coalesce(p_expected_versions->>w.id::text,'') <> w.version::text
       ) then
        raise exception 'CONFLICT_VERSION_MISMATCH';
    end if;

    update public.patrol_schedule_windows w
    set start_time = p_start_time,
        end_time = v_expected_end
    where w.patrol_template_id = p_template_id
      and w.active;
end;
$$;

commit;
