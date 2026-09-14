import { createClient } from "npm:@supabase/supabase-js@2";

type Device = {
  id: string;
  organization_id: string;
  active: boolean;
  archived_at: string | null;
  api_token_hash: string;
};

type EventRequest = {
  event_id: string;
  aggregate_type: string;
  aggregate_id: string;
  event_type: string;
  created_at_device: number;
  payload: Record<string, unknown>;
};

type SupabaseClient = ReturnType<typeof createClient>;

const PROPERTY_ID = "10000000-0000-4000-8000-000000000001";
const BLOCK_IDS = [
  "10000000-0000-4000-8000-000000000101",
  "10000000-0000-4000-8000-000000000102",
];
const POINT_NAMES = [
  "Térreo",
  "Garagem",
  "Play",
  "1º andar",
  "2º andar",
  "3º andar",
  "4º andar",
  "5º andar",
  "6º andar",
  "7º andar",
  "8º andar",
  "9º andar",
  "10º andar",
  "11º andar",
  "Cobertura",
];
const SCHEDULE_IDS = [1, 2, 3, 4].map((slot) => scheduleId(slot));

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });

const sha256 = async (value: string) => {
  const bytes = new TextEncoder().encode(value);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
};

function checkpointId(block: number, slot: number) {
  const prefix = block === 1 ? "11000000-0000-4000-8101-" : "12000000-0000-4000-8102-";
  return prefix + String(slot).padStart(12, "0");
}

function initialQrCredentialId(block: number, slot: number) {
  const prefix = block === 1 ? "21000000-0000-4000-8201-" : "22000000-0000-4000-8202-";
  return prefix + String(slot).padStart(12, "0");
}

function scheduleId(slot: number) {
  return "31000000-0000-4000-8300-" + String(slot).padStart(12, "0");
}

function qrRawValue(credentialId: string, checkpoint: string, version: number) {
  return `porteirinho:v1:${credentialId}:${checkpoint}-v${version}`;
}

function minuteToTime(value: number) {
  const normalized = ((value % 1440) + 1440) % 1440;
  const hour = Math.floor(normalized / 60);
  const minute = normalized % 60;
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}:00`;
}

async function requireNoError(result: { error: { message?: string; code?: string } | null }, label: string) {
  if (result.error) throw new Error(`${label}:${result.error.code ?? result.error.message ?? "unknown"}`);
}

async function ensureFixedStructure(supabase: SupabaseClient, organizationId: string) {
  await requireNoError(
    await supabase.from("location_nodes").upsert({
      id: PROPERTY_ID,
      organization_id: organizationId,
      parent_id: null,
      kind: "PROPERTY",
      name: "Condomínio",
      active: true,
      archived_at: null,
      updated_at: new Date().toISOString(),
    }, { onConflict: "id", ignoreDuplicates: true }),
    "property_insert_failed",
  );

  for (let block = 1; block <= 2; block += 1) {
    const blockId = BLOCK_IDS[block - 1];
    await requireNoError(
      await supabase.from("location_nodes").upsert({
        id: blockId,
        organization_id: organizationId,
        parent_id: PROPERTY_ID,
        kind: "BLOCK",
        name: `Bloco ${block}`,
        active: true,
        archived_at: null,
        updated_at: new Date().toISOString(),
      }, { onConflict: "id", ignoreDuplicates: true }),
      "block_insert_failed",
    );

    for (let slot = 1; slot <= 15; slot += 1) {
      const pointId = checkpointId(block, slot);
      const credentialId = initialQrCredentialId(block, slot);
      const raw = qrRawValue(credentialId, pointId, 1);
      const hash = await sha256(raw);

      await requireNoError(
        await supabase.from("checkpoints").upsert({
          id: pointId,
          organization_id: organizationId,
          location_node_id: blockId,
          name: POINT_NAMES[slot - 1],
          description: "Ponto fixo de ronda",
          sequence_hint: slot,
          minimum_travel_seconds_from_previous: 0,
          is_fixed: true,
          fixed_slot: slot,
          active: true,
          archived_at: null,
          updated_at: new Date().toISOString(),
        }, { onConflict: "id", ignoreDuplicates: true }),
        "checkpoint_insert_failed",
      );

      await requireNoError(
        await supabase.from("qr_credentials").upsert({
          id: credentialId,
          organization_id: organizationId,
          checkpoint_id: pointId,
          token_hash: hash,
          version: 1,
          status: "ACTIVE",
          revoked_at: null,
        }, { onConflict: "id", ignoreDuplicates: true }),
        "qr_insert_failed",
      );
    }
  }

  const allPointIds = [1, 2].flatMap((block) =>
    Array.from({ length: 15 }, (_, index) => checkpointId(block, index + 1))
  );

  for (let slot = 1; slot <= 4; slot += 1) {
    const id = scheduleId(slot);
    const startMinute = (slot - 1) * 360;
    await requireNoError(
      await supabase.from("patrol_schedules").upsert({
        id,
        organization_id: organizationId,
        property_id: PROPERTY_ID,
        name: `Ronda ${slot}`,
        weekdays: [1, 2, 3, 4, 5, 6, 7],
        start_time: minuteToTime(startMinute),
        end_time: minuteToTime(startMinute + 60),
        tolerance_minutes: 15,
        is_fixed: true,
        fixed_slot: slot,
        active: true,
        archived_at: null,
        updated_at: new Date().toISOString(),
      }, { onConflict: "id", ignoreDuplicates: true }),
      "schedule_insert_failed",
    );

    const links = allPointIds.map((pointId, index) => ({
      organization_id: organizationId,
      schedule_id: id,
      checkpoint_id: pointId,
      sequence: index + 1,
    }));
    await requireNoError(
      await supabase.from("schedule_checkpoints").upsert(links, { onConflict: "schedule_id,checkpoint_id" }),
      "schedule_links_upsert_failed",
    );
  }
}

async function materializeAdminEvent(
  supabase: SupabaseClient,
  organizationId: string,
  event: EventRequest,
) {
  await ensureFixedStructure(supabase, organizationId);

  if (event.event_type === "FIXED_STRUCTURE_ENSURED") return;

  if (event.event_type === "CHECKPOINT_CREATED") {
    const pointId = String(event.payload.checkpoint_id ?? "");
    const blockId = String(event.payload.block_id ?? "");
    const name = String(event.payload.name ?? "").trim();
    const credentialId = String(event.payload.qr_credential_id ?? "");
    const tokenHash = String(event.payload.qr_token_hash ?? "");
    const version = Number(event.payload.qr_version ?? 1);
    const sequenceHint = Number(event.payload.sequence_hint ?? 16);
    if (!pointId || !BLOCK_IDS.includes(blockId) || !name || !credentialId || !tokenHash) {
      throw new Error("invalid_checkpoint_created_payload");
    }

    await requireNoError(
      await supabase.from("checkpoints").upsert({
        id: pointId,
        organization_id: organizationId,
        location_node_id: blockId,
        name,
        description: "Ponto extra de ronda",
        sequence_hint: sequenceHint,
        minimum_travel_seconds_from_previous: 0,
        is_fixed: false,
        fixed_slot: null,
        active: true,
        archived_at: null,
        updated_at: new Date().toISOString(),
      }),
      "extra_checkpoint_upsert_failed",
    );

    await requireNoError(
      await supabase.from("qr_credentials").upsert({
        id: credentialId,
        organization_id: organizationId,
        checkpoint_id: pointId,
        token_hash: tokenHash,
        version,
        status: "ACTIVE",
        revoked_at: null,
      }),
      "extra_qr_upsert_failed",
    );

    for (const schedule of SCHEDULE_IDS) {
      const { data: rows, error: sequenceError } = await supabase
        .from("schedule_checkpoints")
        .select("sequence")
        .eq("organization_id", organizationId)
        .eq("schedule_id", schedule)
        .order("sequence", { ascending: false })
        .limit(1);
      if (sequenceError) throw new Error(`schedule_sequence_lookup_failed:${sequenceError.code}`);
      const nextSequence = ((rows?.[0]?.sequence as number | undefined) ?? 0) + 1;
      await requireNoError(
        await supabase.from("schedule_checkpoints").upsert({
          organization_id: organizationId,
          schedule_id: schedule,
          checkpoint_id: pointId,
          sequence: nextSequence,
        }, { onConflict: "schedule_id,checkpoint_id" }),
        "extra_schedule_link_failed",
      );
    }
    return;
  }

  if (event.event_type === "QR_REPLACED") {
    const pointId = String(event.payload.checkpoint_id ?? "");
    const credentialId = String(event.payload.qr_credential_id ?? "");
    const tokenHash = String(event.payload.qr_token_hash ?? "");
    const version = Number(event.payload.qr_version ?? 0);
    if (!pointId || !credentialId || !tokenHash || version <= 1) {
      throw new Error("invalid_qr_replaced_payload");
    }

    await requireNoError(
      await supabase.from("qr_credentials")
        .update({ status: "REVOKED", revoked_at: new Date().toISOString() })
        .eq("organization_id", organizationId)
        .eq("checkpoint_id", pointId)
        .eq("status", "ACTIVE"),
      "old_qr_revoke_failed",
    );

    await requireNoError(
      await supabase.from("qr_credentials").upsert({
        id: credentialId,
        organization_id: organizationId,
        checkpoint_id: pointId,
        token_hash: tokenHash,
        version,
        status: "ACTIVE",
        revoked_at: null,
      }),
      "replacement_qr_upsert_failed",
    );
    return;
  }

  if (event.event_type === "SCHEDULE_UPDATED") {
    const id = String(event.payload.schedule_id ?? "");
    const name = String(event.payload.name ?? "").trim();
    const startMinute = Number(event.payload.start_minute_of_day ?? -1);
    const endMinute = Number(event.payload.end_minute_of_day ?? -1);
    if (!SCHEDULE_IDS.includes(id) || !name || startMinute < 0 || startMinute > 1439 || endMinute < 0 || endMinute > 1439) {
      throw new Error("invalid_schedule_updated_payload");
    }

    await requireNoError(
      await supabase.from("patrol_schedules").update({
        name,
        start_time: minuteToTime(startMinute),
        end_time: minuteToTime(endMinute),
        updated_at: new Date().toISOString(),
      }).eq("organization_id", organizationId).eq("id", id).eq("is_fixed", true),
      "schedule_update_failed",
    );
    return;
  }

  throw new Error(`unsupported_admin_event:${event.event_type}`);
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

  const publicId = request.headers.get("x-device-id");
  const token = request.headers.get("x-device-token");
  if (!publicId || !token) return json({ error: "device_credentials_required" }, 401);

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false, autoRefreshToken: false } },
  );

  const { data, error: deviceError } = await supabase
    .from("devices")
    .select("id,organization_id,active,archived_at,api_token_hash")
    .eq("public_id", publicId)
    .maybeSingle<Device>();

  if (deviceError) return json({ error: "device_lookup_failed" }, 503);
  if (!data || !data.active || data.archived_at) return json({ error: "device_not_authorized" }, 403);
  if ((await sha256(token)) !== data.api_token_hash) return json({ error: "device_token_invalid" }, 403);

  let event: EventRequest;
  try {
    event = await request.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }
  if (!event.event_id || !event.aggregate_type || !event.aggregate_id || !event.event_type || !Number.isFinite(event.created_at_device)) {
    return json({ error: "invalid_event" }, 422);
  }

  const { error } = await supabase.from("ingested_events").upsert(
    {
      event_id: event.event_id,
      organization_id: data.organization_id,
      device_id: data.id,
      aggregate_type: event.aggregate_type,
      aggregate_id: event.aggregate_id,
      event_type: event.event_type,
      created_at_device_ms: event.created_at_device,
      payload: event.payload ?? {},
    },
    { onConflict: "event_id", ignoreDuplicates: true },
  );

  if (error) return json({ error: "ingestion_failed", detail: error.code }, 500);

  if (event.aggregate_type === "ADMIN_CONFIG") {
    try {
      await materializeAdminEvent(supabase, data.organization_id, event);
      await supabase.from("ingested_events").update({
        processing_status: "PROCESSED",
        processing_error: null,
      }).eq("event_id", event.event_id);
    } catch (materializeError) {
      const message = materializeError instanceof Error ? materializeError.message : String(materializeError);
      await supabase.from("ingested_events").update({
        processing_status: "REJECTED",
        processing_error: message.slice(0, 500),
      }).eq("event_id", event.event_id);
      return json({ error: "configuration_materialization_failed", detail: message }, 500);
    }
  }

  await supabase.from("devices").update({
    last_activity_at: new Date(event.created_at_device).toISOString(),
    last_sync_at: new Date().toISOString(),
  }).eq("id", data.id);

  return json({ accepted: true, event_id: event.event_id });
});
