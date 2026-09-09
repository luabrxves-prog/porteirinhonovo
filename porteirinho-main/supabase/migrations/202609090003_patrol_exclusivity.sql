begin;

create table public.patrol_reservations (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references public.organizations(id) on delete restrict,
    schedule_id uuid not null references public.patrol_schedules(id) on delete restrict,
    scheduled_window_start timestamptz not null,
    user_id uuid not null references public.app_users(id) on delete restrict,
    device_id uuid not null references public.devices(id) on delete restrict,
    reserved_at timestamptz not null default now(),
    unique (schedule_id, scheduled_window_start)
);

create index patrol_reservations_org_window_idx
    on public.patrol_reservations(organization_id, scheduled_window_start desc);

alter table public.patrol_reservations enable row level security;

grant select on public.patrol_reservations to authenticated;
grant select, insert on public.patrol_reservations to service_role;
revoke insert, update, delete on public.patrol_reservations from anon, authenticated;

create policy tenant_read on public.patrol_reservations
for select to authenticated
using (organization_id = public.current_organization_id());

create trigger no_delete_patrol_reservations
before delete on public.patrol_reservations
for each row execute function public.prevent_immutable_delete();

-- Segunda barreira de segurança: mesmo depois da materialização dos eventos,
-- o banco central aceita apenas uma execução para cada ronda/ciclo de horário.
create unique index if not exists one_execution_per_patrol_cycle_idx
    on public.patrol_executions(schedule_id, scheduled_window_start);

commit;
