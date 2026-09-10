begin;

insert into public.patrol_schedule_policies(
    schedule_id,
    organization_id,
    start_tolerance_minutes,
    end_tolerance_minutes,
    target_duration_minutes,
    minimum_seconds_between_scans,
    updated_at
)
select
    ps.id,
    ps.organization_id,
    ps.start_tolerance_minutes,
    ps.end_tolerance_minutes,
    ps.target_duration_minutes,
    15,
    now()
from public.patrol_schedules ps
on conflict (schedule_id) do nothing;

create or replace function public.enforce_server_patrol_policy()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
declare
    policy_row public.patrol_schedule_policies%rowtype;
begin
    select * into policy_row
    from public.patrol_schedule_policies
    where schedule_id = new.id;

    if found then
        new.start_tolerance_minutes := policy_row.start_tolerance_minutes;
        new.end_tolerance_minutes := policy_row.end_tolerance_minutes;
        new.target_duration_minutes := policy_row.target_duration_minutes;
    end if;

    return new;
end;
$$;

revoke all on function public.enforce_server_patrol_policy() from public, anon, authenticated;
grant execute on function public.enforce_server_patrol_policy() to service_role;

drop trigger if exists enforce_server_patrol_policy on public.patrol_schedules;
create trigger enforce_server_patrol_policy
before update on public.patrol_schedules
for each row execute function public.enforce_server_patrol_policy();

create or replace function public.mirror_patrol_policy_to_schedule()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
begin
    update public.patrol_schedules
    set start_tolerance_minutes = new.start_tolerance_minutes,
        end_tolerance_minutes = new.end_tolerance_minutes,
        target_duration_minutes = new.target_duration_minutes,
        updated_at = now()
    where id = new.schedule_id
      and organization_id = new.organization_id;

    return new;
end;
$$;

revoke all on function public.mirror_patrol_policy_to_schedule() from public, anon, authenticated;
grant execute on function public.mirror_patrol_policy_to_schedule() to service_role;

drop trigger if exists mirror_patrol_policy_to_schedule on public.patrol_schedule_policies;
create trigger mirror_patrol_policy_to_schedule
after insert or update of start_tolerance_minutes, end_tolerance_minutes, target_duration_minutes
on public.patrol_schedule_policies
for each row execute function public.mirror_patrol_policy_to_schedule();

comment on column public.patrol_schedules.start_tolerance_minutes is
    'Read mirror of patrol_schedule_policies. Client/admin writes are ignored when a policy exists.';
comment on column public.patrol_schedules.end_tolerance_minutes is
    'Read mirror of patrol_schedule_policies. Client/admin writes are ignored when a policy exists.';
comment on column public.patrol_schedules.target_duration_minutes is
    'Read mirror of patrol_schedule_policies. Client/admin writes are ignored when a policy exists.';

commit;
