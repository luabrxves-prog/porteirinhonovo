import { createClient } from "npm:@supabase/supabase-js@2";

type Device = {
  id: string;
  organization_id: string;
  active: boolean;
  archived_at: string | null;
  api_token_hash: string;
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

  const url = new URL(request.url);
  const sinceRaw = url.searchParams.get("since") ?? "0";
  const since = Number.parseInt(sinceRaw, 10);
  if (!Number.isFinite(since) || since < 0) return json({ error: "invalid_cursor" }, 422);

  const { data: latestRows, error: latestError } = await supabase
    .from("sync_changes")
    .select("sequence_id")
    .eq("organization_id", device.organization_id)
    .order("sequence_id", { ascending: false })
    .limit(1);

  if (latestError) return json({ error: "cursor_lookup_failed" }, 503);

  const latest = Number(latestRows?.[0]?.sequence_id ?? 0);
  const changed = latest > since;

  const appVersion = request.headers.get("x-app-version");
  const dbVersion = Number.parseInt(request.headers.get("x-database-version") ?? "0", 10);
  const protocolVersion = Number.parseInt(request.headers.get("x-sync-protocol-version") ?? "1", 10);

  await supabase.from("devices").update({
    app_version: appVersion || null,
    database_version: Number.isFinite(dbVersion) && dbVersion > 0 ? dbVersion : null,
    sync_protocol_version: Number.isFinite(protocolVersion) && protocolVersion > 0 ? protocolVersion : 1,
    last_sync_at: new Date().toISOString(),
    last_sync_error: null,
  }).eq("id", device.id);

  return json({
    changed,
    current_cursor: latest,
    previous_cursor: since,
  });
});
