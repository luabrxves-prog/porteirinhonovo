import { createClient } from "npm:@supabase/supabase-js@2";

type Device = {
  id: string;
  organization_id: string;
  active: boolean;
  archived_at: string | null;
  api_token_hash: string;
};

type ReserveRequest = {
  schedule_id: string;
  user_id: string;
  scheduled_window_start_ms: number;
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

  const { data: device, error: deviceError } = await supabase
    .from("devices")
    .select("id,organization_id,active,archived_at,api_token_hash")
    .eq("public_id", publicId)
    .maybeSingle<Device>();

  if (deviceError) return json({ error: "device_lookup_failed" }, 503);
  if (!device || !device.active || device.archived_at) return json({ error: "device_not_authorized" }, 403);
  if ((await sha256(token)) !== device.api_token_hash) return json({ error: "device_token_invalid" }, 403);

  let body: ReserveRequest;
  try {
    body = await request.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  if (!body.schedule_id || !body.user_id || !Number.isFinite(body.scheduled_window_start_ms)) {
    return json({ error: "invalid_reservation" }, 422);
  }

  const windowStart = new Date(body.scheduled_window_start_ms).toISOString();

  const [{ data: user }, { data: schedule }] = await Promise.all([
    supabase.from("app_users").select("id,role,active,archived_at").eq("organization_id", device.organization_id).eq("id", body.user_id).maybeSingle(),
    supabase.from("patrol_schedules").select("id,fixed_slot,active,archived_at").eq("organization_id", device.organization_id).eq("id", body.schedule_id).maybeSingle(),
  ]);

  if (!user || !user.active || user.archived_at || user.role !== "GATEKEEPER") {
    return json({ error: "gatekeeper_not_authorized" }, 403);
  }
  if (!schedule || !schedule.active || schedule.archived_at || schedule.fixed_slot < 1 || schedule.fixed_slot > 4) {
    return json({ error: "patrol_not_available" }, 422);
  }

  const { error: insertError } = await supabase.from("patrol_reservations").insert({
    organization_id: device.organization_id,
    schedule_id: body.schedule_id,
    scheduled_window_start: windowStart,
    user_id: body.user_id,
    device_id: device.id,
  });

  if (!insertError) {
    return json({ reserved: true, schedule_id: body.schedule_id, user_id: body.user_id, scheduled_window_start_ms: body.scheduled_window_start_ms });
  }

  if (insertError.code !== "23505") {
    return json({ error: "reservation_failed", detail: insertError.code }, 503);
  }

  const { data: existing, error: existingError } = await supabase
    .from("patrol_reservations")
    .select("user_id,device_id,reserved_at")
    .eq("organization_id", device.organization_id)
    .eq("schedule_id", body.schedule_id)
    .eq("scheduled_window_start", windowStart)
    .maybeSingle();

  if (existingError || !existing) return json({ error: "reservation_conflict" }, 409);

  if (existing.user_id === body.user_id && existing.device_id === device.id) {
    return json({ reserved: true, idempotent: true, schedule_id: body.schedule_id, user_id: body.user_id, scheduled_window_start_ms: body.scheduled_window_start_ms });
  }

  return json({
    error: "patrol_already_reserved",
    message: "Esta ronda já foi iniciada ou concluída por outro porteiro.",
    reserved_by_user_id: existing.user_id,
    reserved_at: existing.reserved_at,
  }, 409);
});
