begin;

alter table public.app_users
    add column if not exists must_change_pin boolean not null default false,
    add column if not exists pin_issued_at timestamptz;

alter table public.checkpoints
    add column if not exists fixed boolean not null default false,
    add column if not exists system_key text;

create unique index if not exists checkpoints_fixed_system_key_idx
    on public.checkpoints(organization_id, system_key)
    where system_key is not null and archived_at is null;

alter table public.patrol_schedules
    add column if not exists fixed_slot smallint;

alter table public.patrol_schedules
    add constraint patrol_schedules_fixed_slot_check check (fixed_slot is null or fixed_slot between 1 and 4);

create unique index if not exists patrol_schedules_four_fixed_slots_idx
    on public.patrol_schedules(organization_id, fixed_slot)
    where fixed_slot is not null and archived_at is null;

create table public.patrol_schedule_policies (
    schedule_id uuid primary key references public.patrol_schedules(id) on delete cascade,
    organization_id uuid not null references public.organizations(id),
    start_tolerance_minutes integer not null default 10 check (start_tolerance_minutes between 0 and 180),
    end_tolerance_minutes integer not null default 10 check (end_tolerance_minutes between 0 and 180),
    target_duration_minutes integer not null default 60 check (target_duration_minutes between 1 and 720),
    minimum_seconds_between_scans integer not null default 15 check (minimum_seconds_between_scans between 1 and 300),
    updated_at timestamptz not null default now()
);

alter table public.patrol_schedule_policies enable row level security;
create policy patrol_policy_read on public.patrol_schedule_policies for select to authenticated
    using (organization_id = public.current_organization_id());

comment on table public.patrol_schedule_policies is
    'Operational policy visible to condominium admins but writable only by trusted backend/service role.';

alter table public.occurrences
    add column if not exists checkpoint_id uuid references public.checkpoints(id) on delete restrict;

create index if not exists occurrences_checkpoint_idx
    on public.occurrences(organization_id, checkpoint_id, created_at_device desc);

create or replace view public.patrol_report_rows
with (security_invoker = true)
as
select
    pe.organization_id,
    pe.id as execution_id,
    ps.name as patrol_name,
    au.display_name as gatekeeper_name,
    pe.started_at_device,
    pe.ended_at_device,
    pe.status,
    pe.suspicious,
    count(distinct cv.id) as checkpoints_completed,
    count(distinct cv.id) filter (where cv.suspicious) as suspicious_scans,
    count(distinct o.id) as observations,
    count(distinct a.id) filter (where a.resolved = false) as unresolved_alerts
from public.patrol_executions pe
join public.patrol_schedules ps on ps.id = pe.schedule_id
join public.app_users au on au.id = pe.user_id
left join public.checkpoint_visits cv on cv.execution_id = pe.id
left join public.occurrences o on o.execution_id = pe.id
left join public.alerts a on a.execution_id = pe.id
group by pe.organization_id, pe.id, ps.name, au.display_name, pe.started_at_device, pe.ended_at_device, pe.status, pe.suspicious;

grant select on public.patrol_report_rows to authenticated;

commit;
