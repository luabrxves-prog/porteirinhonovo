begin;

alter table public.devices
    add column if not exists app_version text,
    add column if not exists database_version integer,
    add column if not exists sync_protocol_version integer not null default 1,
    add column if not exists last_sync_error text;

create table if not exists public.sync_changes (
    sequence_id bigint generated always as identity primary key,
    organization_id uuid not null references public.organizations(id) on delete restrict,
    entity_type text not null,
    changed_at timestamptz not null default now()
);

create index if not exists sync_changes_org_sequence_idx
    on public.sync_changes(organization_id, sequence_id);

alter table public.sync_changes enable row level security;
revoke all on public.sync_changes from public, anon, authenticated;
grant select, insert on public.sync_changes to service_role;

create or replace function public.record_sync_change()
returns trigger
language plpgsql
security invoker
set search_path = public
as $$
declare
    org_id uuid;
begin
    org_id := coalesce(
        nullif(to_jsonb(new)->>'organization_id', '')::uuid,
        nullif(to_jsonb(old)->>'organization_id', '')::uuid
    );

    if org_id is not null then
        insert into public.sync_changes(organization_id, entity_type)
        values (org_id, tg_table_name);
    end if;

    return coalesce(new, old);
end;
$$;

revoke all on function public.record_sync_change() from public, anon, authenticated;
grant execute on function public.record_sync_change() to service_role;

do $$
declare
    table_name text;
    trigger_name text;
begin
    foreach table_name in array array[
        'app_users',
        'location_nodes',
        'checkpoints',
        'qr_credentials',
        'patrol_schedules',
        'schedule_checkpoints',
        'schedule_assignees',
        'alerts'
    ] loop
        trigger_name := 'sync_change_' || table_name;
        execute format('drop trigger if exists %I on public.%I', trigger_name, table_name);
        execute format(
            'create trigger %I after insert or update or delete on public.%I for each row execute function public.record_sync_change()',
            trigger_name,
            table_name
        );
    end loop;
end;
$$;

-- Establish an initial cursor boundary for every existing organization.
insert into public.sync_changes(organization_id, entity_type)
select id, 'FULL_SNAPSHOT_REQUIRED'
from public.organizations
where active;

commit;
