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
    add column if not exists fixed_slot smallint,
    add column if not exists start_tolerance_minutes integer not null default 10,
    add column if not exists end_tolerance_minutes integer not null default 10,
    add column if not exists target_duration_minutes integer not null default 60;

alter table public.patrol_schedules
    add constraint patrol_schedules_fixed_slot_check check (fixed_slot is null or fixed_slot between 1 and 4),
    add constraint patrol_schedules_start_tolerance_check check (start_tolerance_minutes between 0 and 180),
    add constraint patrol_schedules_end_tolerance_check check (end_tolerance_minutes between 0 and 180),
    add constraint patrol_schedules_target_duration_check check (target_duration_minutes between 1 and 720);

create unique index if not exists patrol_schedules_four_fixed_slots_idx
    on public.patrol_schedules(organization_id, fixed_slot)
    where fixed_slot is not null and archived_at is null;

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
    count(cv.id) as checkpoints_completed,
    count(*) filter (where cv.suspicious) as suspicious_scans,
    count(o.id) as observations,
    count(a.id) filter (where a.resolved = false) as unresolved_alerts
from public.patrol_executions pe
join public.patrol_schedules ps on ps.id = pe.schedule_id
join public.app_users au on au.id = pe.user_id
left join public.checkpoint_visits cv on cv.execution_id = pe.id
left join public.occurrences o on o.execution_id = pe.id
left join public.alerts a on a.execution_id = pe.id
group by pe.organization_id, pe.id, ps.name, au.display_name, pe.started_at_device, pe.ended_at_device, pe.status, pe.suspicious;

grant select on public.patrol_report_rows to authenticated;

commit;
