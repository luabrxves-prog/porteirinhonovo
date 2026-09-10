begin;

grant usage on schema private to service_role;

create or replace function public.admin_get_checkpoint_qr(
    p_organization_id uuid,
    p_checkpoint_id uuid
)
returns jsonb
language sql
security invoker
set search_path = public, private
as $$
    select jsonb_build_object(
        'checkpoint_id', qc.checkpoint_id,
        'qr_credential_id', qc.id,
        'version', qc.version,
        'raw_payload', qp.raw_payload,
        'issued_at_ms', floor(extract(epoch from qc.issued_at) * 1000)::bigint
    )
    from public.qr_credentials qc
    join private.qr_payloads qp on qp.qr_credential_id = qc.id
    where qc.organization_id = p_organization_id
      and qc.checkpoint_id = p_checkpoint_id
      and qc.status = 'ACTIVE'
      and qc.revoked_at is null
    limit 1
$$;

revoke all on function public.admin_get_checkpoint_qr(uuid, uuid) from public, anon, authenticated;
grant execute on function public.admin_get_checkpoint_qr(uuid, uuid) to service_role;

create or replace function public.admin_replace_checkpoint_qr(
    p_organization_id uuid,
    p_checkpoint_id uuid
)
returns jsonb
language plpgsql
security invoker
set search_path = public, private
as $$
declare
    checkpoint_row public.checkpoints%rowtype;
    credential_id uuid;
    next_version integer;
    raw_qr text;
begin
    select * into checkpoint_row
    from public.checkpoints
    where id = p_checkpoint_id
      and organization_id = p_organization_id
      and active
      and archived_at is null
    for update;

    if not found then
        raise exception using errcode = 'P0001', message = 'CHECKPOINT_NOT_FOUND';
    end if;

    select coalesce(max(version), 0) + 1
    into next_version
    from public.qr_credentials
    where organization_id = p_organization_id
      and checkpoint_id = p_checkpoint_id;

    update public.qr_credentials
    set status = 'REVOKED', revoked_at = now()
    where organization_id = p_organization_id
      and checkpoint_id = p_checkpoint_id
      and status = 'ACTIVE';

    credential_id := gen_random_uuid();
    raw_qr := 'porteirinho:v1:' || credential_id::text || ':' || gen_random_uuid()::text;

    insert into public.qr_credentials(
        id, organization_id, checkpoint_id, token_hash, version, status, issued_at
    ) values (
        credential_id,
        p_organization_id,
        p_checkpoint_id,
        encode(digest(raw_qr, 'sha256'), 'hex'),
        next_version,
        'ACTIVE',
        now()
    );

    insert into private.qr_payloads(qr_credential_id, organization_id, raw_payload)
    values (credential_id, p_organization_id, raw_qr);

    return jsonb_build_object(
        'checkpoint_id', p_checkpoint_id,
        'qr_credential_id', credential_id,
        'version', next_version,
        'raw_payload', raw_qr,
        'issued_at_ms', floor(extract(epoch from now()) * 1000)::bigint
    );
end;
$$;

revoke all on function public.admin_replace_checkpoint_qr(uuid, uuid) from public, anon, authenticated;
grant execute on function public.admin_replace_checkpoint_qr(uuid, uuid) to service_role;

commit;
