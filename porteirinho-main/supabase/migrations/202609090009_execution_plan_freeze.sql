begin;

create or replace function public.freeze_execution_checkpoint_plan()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
    insert into public.execution_checkpoint_plan(
        organization_id, execution_id, checkpoint_id, sequence
    )
    select
        new.organization_id,
        new.id,
        sc.checkpoint_id,
        sc.sequence
    from public.schedule_checkpoints sc
    where sc.schedule_id = new.schedule_id
    on conflict (execution_id, checkpoint_id) do nothing;

    update public.patrol_reservations
    set claimed_at = coalesce(claimed_at, now()),
        execution_id = coalesce(execution_id, new.id),
        expires_at = null
    where organization_id = new.organization_id
      and schedule_id = new.schedule_id
      and scheduled_window_start = new.scheduled_window_start
      and user_id = new.user_id
      and device_id = new.device_id;

    return new;
end;
$$;

revoke all on function public.freeze_execution_checkpoint_plan() from public, anon, authenticated;
grant execute on function public.freeze_execution_checkpoint_plan() to service_role;

drop trigger if exists freeze_execution_checkpoint_plan on public.patrol_executions;
create trigger freeze_execution_checkpoint_plan
after insert on public.patrol_executions
for each row execute function public.freeze_execution_checkpoint_plan();

create or replace function public.protect_active_patrol_plan()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
declare
    affected_schedule_id uuid;
begin
    affected_schedule_id := coalesce(new.schedule_id, old.schedule_id);

    if exists (
        select 1
        from public.patrol_executions pe
        where pe.schedule_id = affected_schedule_id
          and pe.status = 'IN_PROGRESS'
    ) then
        raise exception using
            errcode = 'P0001',
            message = 'PATROL_PLAN_ACTIVE';
    end if;

    return coalesce(new, old);
end;
$$;

revoke all on function public.protect_active_patrol_plan() from public, anon, authenticated;
grant execute on function public.protect_active_patrol_plan() to service_role;

drop trigger if exists protect_active_patrol_plan on public.schedule_checkpoints;
create trigger protect_active_patrol_plan
before insert or update or delete on public.schedule_checkpoints
for each row execute function public.protect_active_patrol_plan();

commit;
