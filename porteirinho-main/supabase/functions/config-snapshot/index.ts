import { createClient } from "npm:@supabase/supabase-js@2";

type Device = {
  id: string;
  organization_id: string;
  active: boolean;
  archived_at: string | null;
  api_token_hash: string;
};

const PROPERTY_ID = "10000000-0000-4000-8000-000000000001";
const BLOCK_IDS = [
  "10000000-0000-4000-8000-000000000101",
  "10000000-0000-4000-8000-000000000102",
];
const SCHEDULE_IDS = [1, 2, 3, 4].map((slot) =>
  "31000000-0000-4000-8300-" + String(slot).padStart(12, "0")
);

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

Deno.serve(async (request) => {
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405);

  const publicId = request.headers.get("x-device-id");
  const token = request.headers.get("x-device-token");
  if (!publicId || !token) return json({ error: "device_credentials_required" }, 401);

  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { persistSession: false, autoRefreshToken: false } },
  );

  const { data: device, error: deviceError } = await supabase
    .from("devices")
    .select("id,organization_id,active,archived_at,api_token_hash")
    .eq("public_id", publicId)
    .maybeSingle<Device>();

  if (deviceError) return json({ error: "device_lookup_failed" }, 503);
  if (!device || !device.active || device.archived_at) return json({ error: "device_not_authorized" }, 403);
  if ((await sha256(token)) !== device.api_token_hash) return json({ error: "device_token_invalid" }, 403);

  const organizationId = device.organization_id;
  const { data: locations, error: locationError } = await supabase
    .from("location_nodes")
    .select("id,parent_id,kind,name,active,archived_at,updated_at")
    .eq("organization_id", organizationId)
    .in("id", [PROPERTY_ID, ...BLOCK_IDS]);
  if (locationError) return json({ error: "location_snapshot_failed", detail: locationError.code }, 500);

  const { data: checkpoints, error: checkpointError } = await supabase
    .from("checkpoints")
    .select("id,location_node_id,name,description,sequence_hint,minimum_travel_seconds_from_previous,active,archived_at,updated_at,is_fixed")
    .eq("organization_id", organizationId)
    .in("location_node_id", BLOCK_IDS)
    .is("archived_at", null);
  if (checkpointError) return json({ error: "checkpoint_snapshot_failed", detail: checkpointError.code }, 500);

  const checkpointIds = (checkpoints ?? []).map((item) => item.id);
  const qrQuery = supabase
    .from("qr_credentials")
    .select("id,checkpoint_id,token_hash,version,status,issued_at,revoked_at")
    .eq("organization_id", organizationId)
    .eq("status", "ACTIVE")
    .is("revoked_at", null);
  const { data: qrCredentials, error: qrError } = checkpointIds.length > 0
    ? await qrQuery.in("checkpoint_id", checkpointIds)
    : { data: [], error: null };
  if (qrError) return json({ error: "qr_snapshot_failed", detail: qrError.code }, 500);

  const { data: schedules, error: scheduleError } = await supabase
    .from("patrol_schedules")
    .select("id,property_id,name,weekdays,start_time,end_time,tolerance_minutes,active,archived_at,updated_at,is_fixed,fixed_slot")
    .eq("organization_id", organizationId)
    .in("id", SCHEDULE_IDS)
    .eq("is_fixed", true)
    .is("archived_at", null);
  if (scheduleError) return json({ error: "schedule_snapshot_failed", detail: scheduleError.code }, 500);

  const { data: links, error: linksError } = await supabase
    .from("schedule_checkpoints")
    .select("schedule_id,checkpoint_id,sequence")
    .eq("organization_id", organizationId)
    .in("schedule_id", SCHEDULE_IDS)
    .order("sequence", { ascending: true });
  if (linksError) return json({ error: "schedule_links_snapshot_failed", detail: linksError.code }, 500);

  await supabase.from("devices").update({ last_sync_at: new Date().toISOString() }).eq("id", device.id);

  return json({
    server_time: new Date().toISOString(),
    locations: locations ?? [],
    checkpoints: checkpoints ?? [],
    qr_credentials: qrCredentials ?? [],
    schedules: schedules ?? [],
    schedule_checkpoints: links ?? [],
  });
});
