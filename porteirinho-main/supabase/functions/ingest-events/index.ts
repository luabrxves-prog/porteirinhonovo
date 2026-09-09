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

  await supabase.from("devices").update({
    last_activity_at: new Date(event.created_at_device).toISOString(),
    last_sync_at: new Date().toISOString(),
  }).eq("id", data.id);

  return json({ accepted: true, event_id: event.event_id });
});
