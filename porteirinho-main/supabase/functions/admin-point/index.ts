import { createClient } from "npm:@supabase/supabase-js@2.57.4";

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
const sha256 = async (value: string) => {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
};

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const publicId = request.headers.get("x-device-id");
  const token = request.headers.get("x-device-token");
  if (!publicId || !token) return json({ error: "device_credentials_required" }, 401);

  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data: device, error: deviceError } = await supabase.from("devices")
    .select("id,organization_id,active,archived_at,api_token_hash,can_admin")
    .eq("public_id", publicId).maybeSingle();
  if (deviceError) return json({ error: "device_lookup_failed" }, 503);
  if (!device || !device.active || device.archived_at) return json({ error: "device_not_authorized" }, 403);
  if ((await sha256(token)) !== device.api_token_hash) return json({ error: "device_token_invalid" }, 403);
  if (!device.can_admin) return json({ error: "admin_device_required" }, 403);

  let body: { name?: string };
  try { body = await request.json(); } catch { return json({ error: "invalid_json" }, 400); }
  const name = body.name?.trim();
  if (!name || name.length < 2 || name.length > 100) return json({ error: "invalid_name" }, 422);

  const { data: place, error: placeError } = await supabase.from("location_nodes")
    .select("id").eq("organization_id", device.organization_id).eq("kind", "PLACE").is("archived_at", null).limit(1).maybeSingle();
  if (placeError) return json({ error: "place_lookup_failed" }, 503);
  if (!place) return json({ error: "place_not_found" }, 409);

  const checkpointId = crypto.randomUUID();
  const { error: insertError } = await supabase.from("checkpoints").insert({
    id: checkpointId,
    organization_id: device.organization_id,
    location_node_id: place.id,
    name,
    minimum_travel_seconds_from_previous: 15,
    fixed: false,
    active: true,
  });
  if (insertError) return json({ error: "checkpoint_create_failed", detail: insertError.code }, 500);

  const { data: schedules, error: schedulesError } = await supabase.from("patrol_schedules")
    .select("id").eq("organization_id", device.organization_id).gte("fixed_slot", 1).lte("fixed_slot", 4).is("archived_at", null);
  if (schedulesError) return json({ error: "schedule_lookup_failed" }, 503);

  for (const schedule of schedules ?? []) {
    const { data: last } = await supabase.from("schedule_checkpoints").select("sequence")
      .eq("schedule_id", schedule.id).order("sequence", { ascending: false }).limit(1).maybeSingle();
    const { error } = await supabase.from("schedule_checkpoints").insert({
      organization_id: device.organization_id,
      schedule_id: schedule.id,
      checkpoint_id: checkpointId,
      sequence: Number(last?.sequence ?? 0) + 1,
    });
    if (error) return json({ error: error.message.includes("PATROL_PLAN_ACTIVE") ? "patrol_active" : "link_create_failed" }, 409);
  }

  return json({ created: true, checkpoint_id: checkpointId, name });
});
