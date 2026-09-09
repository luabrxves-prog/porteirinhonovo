begin;

create extension if not exists pgcrypto;

create table public.organizations (
    id uuid primary key default gen_random_uuid(),
    name text not null check (char_length(name) between 2 and 160),
    active boolean not null default true,
    created_at timestamptz not null default now(),
    archived_at timestamptz
);

create table public.app_users (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references public.organizations(id),
    auth_user_id uuid unique references auth.users(id),
    display_name text not null,
    photo_path text,
    role text not null check (role in ('ADMIN', 'GATEKEEPER')),
    pin_digest text,
    pin_changed_at timestamptz,
    failed_pin_attempts integer not null default 0 check (failed_pin_attempts >= 0),
    locked_until timestamptz,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz
);
create index app_users_organization_idx on public.app_users(organization_id, active);

create table public.devices (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references public.organizations(id),
    public_id uuid not null unique,
    name text not null,
    api_token_hash text not null,
    active boolean not null default true,
    last_activity_at timestamptz,
    last_sync_at timestamptz,
    created_at timestamptz not null default now(),
    archived_at timestamptz
);
create index devices_organization_idx on public.devices(organization_id, active);

create table public.location_nodes (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references public.organizations(id),
    parent_id uuid references public.location_nodes(id) on delete restrict,
    kind text not null check (kind in ('PROPERTY', 'BUILDING', 'BLOCK', 'FLOOR', 'PLACE')),
    name text not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz,
    unique (organization_id, parent_id, kind, name)
);
create index location_nodes_parent_idx on public.location_nodes(organization_id, parent_id);

create table public.checkpoints (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references public.organizations(id),
    location_node_id uuid not null references public.location_nodes(id) on delete restrict,
    name text not null,
    description text,
    minimum_travel_seconds_from_previous integer not null default 0 check (minimum_travel_seconds_from_previous >= 0),
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz
);
create index checkpoints_location_idx on public.checkpoints(organization_id, location_node_id, active);

create table public.qr_credentials (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references public.organizations(id),
    checkpoint_id uuid not null references public.checkpoints(id) on delete restrict,
    token_hash text not null unique,
    version integer not null check (version > 0),
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'REVOKED')),
    issued_at timestamptz not null default now(),
    revoked_at timestamptz,
    constraint revoked_qr_has_timestamp check ((status = 'REVOKED') = (revoked_at is not null))
);
create unique index one_active_qr_per_checkpoint_idx
    on public.qr_credentials(checkpoint_id) where status = 'ACTIVE';

create table public.patrol_schedules (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references public.organizations(id),
    property_id uuid not null references public.location_nodes(id) on delete restrict,
    name text not null,
    weekdays smallint[] not null check (weekdays <@ array[1,2,3,4,5,6,7]::smallint[]),
    start_time time not null,
    end_time time not null,
    tolerance_minutes integer not null default 0 check (tolerance_minutes between 0 and 1440),
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz
);
create index patrol_schedules_org_active_idx on public.patrol_schedules(organization_id, active);

create table public.schedule_checkpoints (
    organization_id uuid not null references public.organizations(id),
    schedule_id uuid not null references public.patrol_schedules(id) on delete cascade,
    checkpoint_id uuid not null references public.checkpoints(id) on delete restrict,
    sequence integer not null check (sequence > 0),
    primary key (schedule_id, checkpoint_id),
    unique (schedule_id, sequence)
);

create table public.schedule_assignees (
    organization_id uuid not null references public.organizations(id),
    schedule_id uuid not null references public.patrol_schedules(id) on delete cascade,
    user_id uuid not null references public.app_users(id) on delete restrict,
    primary key (schedule_id, user_id)
);

create table public.shifts (
    id uuid primary key,
    organization_id uuid not null references public.organizations(id),
    user_id uuid not null references public.app_users(id) on delete restrict,
    device_id uuid not null references public.devices(id) on delete restrict,
    started_at_device timestamptz not null,
    started_at_server timestamptz not null default now(),
    ended_at_device timestamptz,
    ended_at_server timestamptz,
    created_at timestamptz not null default now(),
    constraint shift_end_after_start check (ended_at_device is null or ended_at_device >= started_at_device)
);
create unique index one_open_shift_per_user_idx on public.shifts(user_id) where ended_at_device is null;

create table public.patrol_executions (
    id uuid primary key,
    organization_id uuid not null references public.organizations(id),
    schedule_id uuid not null references public.patrol_schedules(id) on delete restrict,
    shift_id uuid not null references public.shifts(id) on delete restrict,
    user_id uuid not null references public.app_users(id) on delete restrict,
    device_id uuid not null references public.devices(id) on delete restrict,
    scheduled_window_start timestamptz not null,
    scheduled_window_end timestamptz not null,
    started_at_device timestamptz not null,
    started_at_server timestamptz not null default now(),
    ended_at_device timestamptz,
    ended_at_server timestamptz,
    status text not null check (status in ('IN_PROGRESS', 'COMPLETED', 'INCOMPLETE', 'LATE', 'MISSED', 'SUSPICIOUS', 'CANCELLED')),
    suspicious boolean not null default false,
    created_at timestamptz not null default now()
);
create index patrol_executions_history_idx on public.patrol_executions(organization_id, started_at_device desc);
create index patrol_executions_status_idx on public.patrol_executions(organization_id, status);
create unique index one_active_patrol_per_user_idx on public.patrol_executions(user_id) where status = 'IN_PROGRESS';

create table public.checkpoint_visits (
    id uuid primary key,
    organization_id uuid not null references public.organizations(id),
    execution_id uuid not null references public.patrol_executions(id) on delete restrict,
    checkpoint_id uuid not null references public.checkpoints(id) on delete restrict,
    qr_credential_id uuid not null references public.qr_credentials(id) on delete restrict,
    scanned_at_device timestamptz not null,
    received_at_server timestamptz not null default now(),
    suspicious boolean not null default false,
    suspicion_reason text,
    unique (execution_id, checkpoint_id)
);
create index checkpoint_visits_execution_idx on public.checkpoint_visits(execution_id, scanned_at_device);

create table public.occurrences (
    id uuid primary key,
    organization_id uuid not null references public.organizations(id),
    execution_id uuid not null references public.patrol_executions(id) on delete restrict,
    category text not null,
    description text not null,
    attachment_path text,
    created_at_device timestamptz not null,
    received_at_server timestamptz not null default now()
);

create table public.alerts (
    id uuid primary key,
    organization_id uuid not null references public.organizations(id),
    type text not null,
    description text not null,
    user_id uuid references public.app_users(id) on delete restrict,
    execution_id uuid references public.patrol_executions(id) on delete restrict,
    device_id uuid references public.devices(id) on delete restrict,
    created_at_device timestamptz not null,
    received_at_server timestamptz not null default now(),
    resolved boolean not null default false,
    resolved_by uuid references public.app_users(id) on delete restrict,
    resolved_at timestamptz
);
create index alerts_open_idx on public.alerts(organization_id, resolved, created_at_device desc);

create table public.audit_logs (
    id uuid primary key default gen_random_uuid(),
    organization_id uuid not null references public.organizations(id),
    actor_user_id uuid references public.app_users(id) on delete restrict,
    operation text not null,
    entity_type text not null,
    entity_id uuid not null,
    previous_value jsonb,
    new_value jsonb,
    created_at timestamptz not null default now()
);
create index audit_logs_timeline_idx on public.audit_logs(organization_id, created_at desc);

create table public.ingested_events (
    event_id uuid primary key,
    organization_id uuid not null references public.organizations(id),
    device_id uuid not null references public.devices(id) on delete restrict,
    aggregate_type text not null,
    aggregate_id text not null,
    event_type text not null,
    created_at_device_ms bigint not null,
    payload jsonb not null,
    received_at_server timestamptz not null default now(),
    processing_status text not null default 'RECEIVED' check (processing_status in ('RECEIVED', 'PROCESSED', 'REJECTED')),
    processing_error text
);
create index ingested_events_processing_idx on public.ingested_events(organization_id, processing_status, received_at_server);

create or replace function public.current_organization_id()
returns uuid
language sql
stable
security definer
set search_path = public
set row_security = off
as $$
    select organization_id from public.app_users where auth_user_id = auth.uid() and active and archived_at is null limit 1
$$;

create or replace function public.current_app_role()
returns text
language sql
stable
security definer
set search_path = public
set row_security = off
as $$
    select role from public.app_users where auth_user_id = auth.uid() and active and archived_at is null limit 1
$$;

revoke all on function public.current_organization_id() from public;
revoke all on function public.current_app_role() from public;
grant execute on function public.current_organization_id() to authenticated;
grant execute on function public.current_app_role() to authenticated;

create or replace function public.prevent_immutable_delete()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Audit and operational records cannot be deleted';
end;
$$;

create trigger no_delete_patrol_executions before delete on public.patrol_executions for each row execute function public.prevent_immutable_delete();
create trigger no_delete_checkpoint_visits before delete on public.checkpoint_visits for each row execute function public.prevent_immutable_delete();
create trigger no_delete_audit_logs before delete on public.audit_logs for each row execute function public.prevent_immutable_delete();
create trigger no_delete_ingested_events before delete on public.ingested_events for each row execute function public.prevent_immutable_delete();

create or replace function public.audit_configuration_change()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    org_id uuid;
    row_id uuid;
begin
    org_id := coalesce((to_jsonb(new)->>'organization_id')::uuid, (to_jsonb(old)->>'organization_id')::uuid);
    row_id := coalesce((to_jsonb(new)->>'id')::uuid, (to_jsonb(old)->>'id')::uuid);
    insert into public.audit_logs(organization_id, actor_user_id, operation, entity_type, entity_id, previous_value, new_value)
    values (
        org_id,
        (select id from public.app_users where auth_user_id = auth.uid() limit 1),
        tg_op,
        tg_table_name,
        row_id,
        case when tg_op = 'INSERT' then null else to_jsonb(old) end,
        case when tg_op = 'DELETE' then null else to_jsonb(new) end
    );
    return new;
end;
$$;

create trigger audit_users after insert or update on public.app_users for each row execute function public.audit_configuration_change();
create trigger audit_devices after insert or update on public.devices for each row execute function public.audit_configuration_change();
create trigger audit_locations after insert or update on public.location_nodes for each row execute function public.audit_configuration_change();
create trigger audit_checkpoints after insert or update on public.checkpoints for each row execute function public.audit_configuration_change();
create trigger audit_qr after insert or update on public.qr_credentials for each row execute function public.audit_configuration_change();
create trigger audit_schedules after insert or update on public.patrol_schedules for each row execute function public.audit_configuration_change();

alter table public.organizations enable row level security;
alter table public.app_users enable row level security;
alter table public.devices enable row level security;
alter table public.location_nodes enable row level security;
alter table public.checkpoints enable row level security;
alter table public.qr_credentials enable row level security;
alter table public.patrol_schedules enable row level security;
alter table public.schedule_checkpoints enable row level security;
alter table public.schedule_assignees enable row level security;
alter table public.shifts enable row level security;
alter table public.patrol_executions enable row level security;
alter table public.checkpoint_visits enable row level security;
alter table public.occurrences enable row level security;
alter table public.alerts enable row level security;
alter table public.audit_logs enable row level security;
alter table public.ingested_events enable row level security;

create policy organization_read on public.organizations for select to authenticated
    using (id = public.current_organization_id());

do $$
declare
    table_name text;
begin
    foreach table_name in array array[
        'app_users', 'devices', 'location_nodes', 'checkpoints', 'qr_credentials',
        'patrol_schedules', 'schedule_checkpoints', 'schedule_assignees', 'shifts',
        'patrol_executions', 'checkpoint_visits', 'occurrences', 'alerts', 'audit_logs', 'ingested_events'
    ] loop
        execute format(
            'create policy tenant_read on public.%I for select to authenticated using (organization_id = public.current_organization_id())',
            table_name
        );
        execute format(
            'create policy admin_write on public.%I for all to authenticated using (organization_id = public.current_organization_id() and public.current_app_role() = ''ADMIN'') with check (organization_id = public.current_organization_id() and public.current_app_role() = ''ADMIN'')',
            table_name
        );
    end loop;
end;
$$;

create policy organization_admin_update on public.organizations for update to authenticated
    using (id = public.current_organization_id() and public.current_app_role() = 'ADMIN')
    with check (id = public.current_organization_id() and public.current_app_role() = 'ADMIN');

revoke delete on public.patrol_executions, public.checkpoint_visits, public.audit_logs, public.ingested_events from authenticated;

commit;
