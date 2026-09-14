import { createClient } from "npm:@supabase/supabase-js@2.57.4";

type Device = {
  id: string;
  organization_id: string;
  active: boolean;
  archived_at: string | null;
  api_token_hash: string;
  can_admin: boolean;
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
    .select("id,organization_id,active,archived_at,api_token_hash,can_admin")
    .eq("public_id", publicId)
    .maybeSingle<Device>();

  if (deviceError) return json({ error: "device_lookup_failed" }, 503);
  if (!device || !device.active || device.archived_at) return json({ error: "device_not_authorized" }, 403);
  if ((await sha256(token)) !== device.api_token_hash) return json({ error: "device_token_invalid" }, 403);
  if (!device.can_admin) return json({ error: "admin_device_required" }, 403);

  let body: { action?: string; checkpoint_id?: string };
  try {
    body = await request.json();
  } catch {
    return json({ error: "invalid_json" }, 400);
  }

  if (!body.checkpoint_id || !["get", "replace"].includes(body.action ?? "")) {
    return json({ error: "invalid_request" }, 422);
  }

  const functionName = body.action === "replace" ? "admin_replace_checkpoint_qr" : "admin_get_checkpoint_qr";
  const { data, error } = await supabase.rpc(functionName, {
    p_organization_id: device.organization_id,
    p_checkpoint_id: body.checkpoint_id,
  });

  if (error) return json({ error: "qr_operation_failed", detail: error.message }, 500);
  if (!data) return json({ error: "qr_not_found" }, 404);

  return json(data);
});
