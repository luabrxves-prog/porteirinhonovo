begin;

drop view if exists public.patrol_report_rows;

create view public.patrol_report_rows
with (security_invoker = true)
as
select
    pe.organization_id,
    pe.id as execution_id,
    ps.name as patrol_name,
    au.display_name as gatekeeper_name,
    pe.scheduled_window_start,
    pe.scheduled_window_end,
    pe.started_at_device,
    pe.ended_at_device,
    case
        when pe.ended_at_device is not null
        then greatest(0, extract(epoch from (pe.ended_at_device - pe.started_at_device))::bigint)
        else null
    end as duration_seconds,
    pe.status,
    pe.suspicious,
    count(distinct ecp.checkpoint_id) as checkpoints_expected,
    count(distinct cv.id) as checkpoints_completed,
    count(distinct cv.id) filter (where cv.suspicious) as suspicious_scans,
    count(distinct o.id) as observations,
    count(distinct a.id) as alerts,
    count(distinct a.id) filter (where a.resolved = false) as unresolved_alerts
from public.patrol_executions pe
join public.patrol_schedules ps on ps.id = pe.schedule_id
join public.app_users au on au.id = pe.user_id
left join public.execution_checkpoint_plan ecp on ecp.execution_id = pe.id
left join public.checkpoint_visits cv on cv.execution_id = pe.id
left join public.occurrences o on o.execution_id = pe.id
left join public.alerts a on a.execution_id = pe.id
group by
    pe.organization_id,
    pe.id,
    ps.name,
    au.display_name,
    pe.scheduled_window_start,
    pe.scheduled_window_end,
    pe.started_at_device,
    pe.ended_at_device,
    pe.status,
    pe.suspicious;

grant select on public.patrol_report_rows to authenticated, service_role;

commit;
