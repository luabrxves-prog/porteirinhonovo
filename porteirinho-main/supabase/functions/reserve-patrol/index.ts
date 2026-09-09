import { createClient } from "npm:@supabase/supabase-js@2";

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
    .maybeSingle();

  if (deviceError) return json({ error: "device_lookup_failed" }, 503);
  if (!device || !device.active || device.archived_at) return json({ error: "device_not_authorized" }, 403);
  if ((await sha256(token)) !== device.api_token_hash) return json({ error: "device_token_invalid" }, 403);

  let body: { schedule_id?: string; user_id?: string; scheduled_window_start?: string };
  try {
    body = await request.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  if (!body.schedule_id || !body.user_id || !body.scheduled_window_start) {
    return json({ error: "invalid_request" }, 422);
  }

  const { data: user, error: userError } = await supabase
    .from("app_users")
    .select("id,organization_id,role,active,archived_at")
    .eq("id", body.user_id)
    .eq("organization_id", device.organization_id)
    .maybeSingle();

  if (userError) return json({ error: "user_lookup_failed" }, 503);
  if (!user || !user.active || user.archived_at || user.role !== "GATEKEEPER") {
    return json({ error: "gatekeeper_not_authorized" }, 403);
  }

  const { data: schedule, error: scheduleError } = await supabase
    .from("patrol_schedules")
    .select("id,organization_id,active,archived_at")
    .eq("id", body.schedule_id)
    .eq("organization_id", device.organization_id)
    .maybeSingle();

  if (scheduleError) return json({ error: "schedule_lookup_failed" }, 503);
  if (!schedule || !schedule.active || schedule.archived_at) return json({ error: "schedule_not_available" }, 409);

  const { data: existing, error: existingError } = await supabase
    .from("patrol_reservations")
    .select("user_id,device_id,reserved_at")
    .eq("schedule_id", body.schedule_id)
    .eq("scheduled_window_start", body.scheduled_window_start)
    .maybeSingle();

  if (existingError) return json({ error: "reservation_lookup_failed" }, 503);
  if (existing) {
    const sameOwner = existing.user_id === body.user_id && existing.device_id === device.id;
    return json(
      {
        reserved: sameOwner,
        already_reserved: true,
        same_owner: sameOwner,
      },
      sameOwner ? 200 : 409,
    );
  }

  const { error: insertError } = await supabase.from("patrol_reservations").insert({
    organization_id: device.organization_id,
    schedule_id: body.schedule_id,
    scheduled_window_start: body.scheduled_window_start,
    user_id: body.user_id,
    device_id: device.id,
  });

  if (insertError?.code === "23505") {
    return json({ reserved: false, already_reserved: true, same_owner: false }, 409);
  }
  if (insertError) return json({ error: "reservation_failed", detail: insertError.code }, 500);

  return json({ reserved: true, already_reserved: false });
});
