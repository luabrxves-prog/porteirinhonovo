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

  if (!body.schedule_id || !body.user_id) return json({ error: "invalid_reservation" }, 422);

  const { data, error } = await supabase.rpc("reserve_current_patrol", {
    p_organization_id: device.organization_id,
    p_schedule_id: body.schedule_id,
    p_user_id: body.user_id,
    p_device_id: device.id,
  });

  if (error) {
    const known = ["PATROL_OUTSIDE_WINDOW", "PATROL_NOT_AVAILABLE", "GATEKEEPER_NOT_AUTHORIZED", "DEVICE_NOT_AUTHORIZED"];
    const message = known.find((item) => error.message.includes(item));
    return json({ error: message ?? "reservation_failed" }, message ? 409 : 503);
  }

  if (!data?.reserved) {
    return json(data ?? { error: "reservation_conflict" }, 409);
  }

  return json(data);
});
