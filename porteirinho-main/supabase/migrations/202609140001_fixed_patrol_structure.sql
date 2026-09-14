begin;

alter table public.checkpoints
    add column if not exists sequence_hint integer not null default 0,
    add column if not exists is_fixed boolean not null default false,
    add column if not exists fixed_slot integer;

alter table public.checkpoints
    drop constraint if exists checkpoints_fixed_slot_check;
alter table public.checkpoints
    add constraint checkpoints_fixed_slot_check
    check ((is_fixed and fixed_slot between 1 and 15) or (not is_fixed and fixed_slot is null));

create unique index if not exists fixed_checkpoint_slot_per_block_idx
    on public.checkpoints(organization_id, location_node_id, fixed_slot)
    where is_fixed;

alter table public.patrol_schedules
    add column if not exists is_fixed boolean not null default false,
    add column if not exists fixed_slot integer;

alter table public.patrol_schedules
    drop constraint if exists patrol_schedules_fixed_slot_check;
alter table public.patrol_schedules
    add constraint patrol_schedules_fixed_slot_check
    check ((is_fixed and fixed_slot between 1 and 4) or (not is_fixed and fixed_slot is null));

create unique index if not exists fixed_schedule_slot_per_org_idx
    on public.patrol_schedules(organization_id, fixed_slot)
    where is_fixed;

create or replace function public.protect_fixed_checkpoint()
returns trigger
language plpgsql
as $$
begin
    if tg_op = 'DELETE' and old.is_fixed then
        raise exception 'Fixed patrol checkpoints cannot be deleted';
    end if;
    if tg_op = 'UPDATE' and old.is_fixed then
        if new.id <> old.id
            or new.organization_id <> old.organization_id
            or new.location_node_id <> old.location_node_id
            or new.name <> old.name
            or new.is_fixed is distinct from old.is_fixed
            or new.fixed_slot is distinct from old.fixed_slot
            or new.archived_at is distinct from old.archived_at
            or new.active is distinct from old.active then
            raise exception 'Fixed patrol checkpoint identity cannot be changed';
        end if;
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

drop trigger if exists protect_fixed_checkpoint_trigger on public.checkpoints;
create trigger protect_fixed_checkpoint_trigger
before update or delete on public.checkpoints
for each row execute function public.protect_fixed_checkpoint();

create or replace function public.protect_fixed_schedule()
returns trigger
language plpgsql
as $$
declare
    fixed_count integer;
begin
    if tg_op = 'INSERT' and new.is_fixed then
        select count(*) into fixed_count
        from public.patrol_schedules
        where organization_id = new.organization_id and is_fixed;
        if fixed_count >= 4 then
            raise exception 'Only four fixed patrol schedules are allowed';
        end if;
    end if;

    if tg_op = 'DELETE' and old.is_fixed then
        raise exception 'Fixed patrol schedules cannot be deleted';
    end if;

    if tg_op = 'UPDATE' and old.is_fixed then
        if new.id <> old.id
            or new.organization_id <> old.organization_id
            or new.property_id <> old.property_id
            or new.weekdays is distinct from old.weekdays
            or new.tolerance_minutes <> old.tolerance_minutes
            or new.active <> old.active
            or new.archived_at is distinct from old.archived_at
            or new.is_fixed is distinct from old.is_fixed
            or new.fixed_slot is distinct from old.fixed_slot then
            raise exception 'Fixed patrol schedule rules cannot be changed';
        end if;
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

drop trigger if exists protect_fixed_schedule_trigger on public.patrol_schedules;
create trigger protect_fixed_schedule_trigger
before insert or update or delete on public.patrol_schedules
for each row execute function public.protect_fixed_schedule();

commit;
