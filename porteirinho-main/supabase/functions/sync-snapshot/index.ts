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

const toMs = (value: string | null | undefined) => value ? new Date(value).getTime() : null;

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

  const org = device.organization_id;
  const cutoff = new Date(Date.now() - 120 * 24 * 60 * 60 * 1000).toISOString();
  const reservationsCutoff = new Date(Date.now() - 48 * 60 * 60 * 1000).toISOString();

  const [
    usersResult,
    locationsResult,
    checkpointsResult,
    qrResult,
    schedulesResult,
    scheduleCheckpointsResult,
    alertsResult,
    reservationsResult,
  ] = await Promise.all([
    supabase.from("app_users").select("id,display_name,photo_path,role,active,pin_salt_base64,pin_hash_base64,must_change_pin,pin_issued_at,pin_changed_at,failed_pin_attempts,locked_until,archived_at,updated_at").eq("organization_id", org),
    supabase.from("location_nodes").select("id,parent_id,kind,name,active,archived_at,updated_at").eq("organization_id", org),
    supabase.from("checkpoints").select("id,location_node_id,name,description,minimum_travel_seconds_from_previous,fixed,system_key,active,archived_at,updated_at").eq("organization_id", org),
    supabase.from("qr_credentials").select("id,checkpoint_id,token_hash,version,status,issued_at,revoked_at").eq("organization_id", org),
    supabase.from("patrol_schedules").select("id,property_id,name,weekdays,start_time,end_time,tolerance_minutes,fixed_slot,start_tolerance_minutes,end_tolerance_minutes,target_duration_minutes,active,archived_at,updated_at").eq("organization_id", org),
    supabase.from("schedule_checkpoints").select("schedule_id,checkpoint_id,sequence").eq("organization_id", org),
    supabase.from("alerts").select("id,type,description,user_id,execution_id,device_id,created_at_device,resolved,resolved_at,updated_at").eq("organization_id", org).gte("created_at_device", cutoff).order("created_at_device", { ascending: false }).limit(1000),
    supabase.from("patrol_reservations").select("schedule_id,scheduled_window_start,user_id,device_id,reserved_at").eq("organization_id", org).gte("scheduled_window_start", reservationsCutoff),
  ]);

  const firstError = [usersResult, locationsResult, checkpointsResult, qrResult, schedulesResult, scheduleCheckpointsResult, alertsResult, reservationsResult]
    .map((item) => item.error)
    .find(Boolean);
  if (firstError) return json({ error: "snapshot_failed", detail: firstError.code }, 503);

  const minuteOfDay = (value: string) => {
    const [hour, minute] = value.split(":").map(Number);
    return hour * 60 + minute;
  };

  return json({
    server_time_ms: Date.now(),
    organization_id: org,
    users: (usersResult.data ?? []).map((row) => ({
      id: row.id,
      display_name: row.display_name,
      photo_url: row.photo_path,
      role: row.role,
      active: row.active,
      pin_salt_base64: row.pin_salt_base64,
      pin_hash_base64: row.pin_hash_base64,
      must_change_pin: row.must_change_pin,
      pin_issued_at_ms: toMs(row.pin_issued_at),
      pin_changed_at_ms: toMs(row.pin_changed_at),
      failed_pin_attempts: row.failed_pin_attempts,
      locked_until_ms: toMs(row.locked_until),
      archived_at_ms: toMs(row.archived_at),
      updated_at_ms: toMs(row.updated_at),
    })),
    locations: (locationsResult.data ?? []).map((row) => ({
      id: row.id,
      parent_id: row.parent_id,
      type: row.kind,
      name: row.name,
      active: row.active,
      archived_at_ms: toMs(row.archived_at),
      updated_at_ms: toMs(row.updated_at),
    })),
    checkpoints: (checkpointsResult.data ?? []).map((row) => ({
      id: row.id,
      location_node_id: row.location_node_id,
      name: row.name,
      description: row.description,
      minimum_travel_seconds_from_previous: row.minimum_travel_seconds_from_previous,
      fixed: row.fixed,
      system_key: row.system_key,
      active: row.active,
      archived_at_ms: toMs(row.archived_at),
      updated_at_ms: toMs(row.updated_at),
    })),
    qr_credentials: (qrResult.data ?? []).map((row) => ({
      id: row.id,
      checkpoint_id: row.checkpoint_id,
      token_hash: row.token_hash,
      version: row.version,
      status: row.status,
      issued_at_ms: toMs(row.issued_at),
      revoked_at_ms: toMs(row.revoked_at),
    })),
    schedules: (schedulesResult.data ?? []).map((row) => ({
      id: row.id,
      property_id: row.property_id,
      name: row.name,
      weekdays_csv: (row.weekdays ?? []).join(","),
      start_minute_of_day: minuteOfDay(row.start_time),
      end_minute_of_day: minuteOfDay(row.end_time),
      tolerance_minutes: row.tolerance_minutes,
      fixed_slot: row.fixed_slot ?? 0,
      start_tolerance_minutes: row.start_tolerance_minutes,
      end_tolerance_minutes: row.end_tolerance_minutes,
      target_duration_minutes: row.target_duration_minutes,
      active: row.active,
      archived_at_ms: toMs(row.archived_at),
      updated_at_ms: toMs(row.updated_at),
    })),
    schedule_checkpoints: scheduleCheckpointsResult.data ?? [],
    alerts: (alertsResult.data ?? []).map((row) => ({
      id: row.id,
      type: row.type,
      description: row.description,
      user_id: row.user_id,
      execution_id: row.execution_id,
      device_id: row.device_id,
      created_at_ms: toMs(row.created_at_device),
      resolved: row.resolved,
      resolved_at_ms: toMs(row.resolved_at),
    })),
    reservations: (reservationsResult.data ?? []).map((row) => ({
      schedule_id: row.schedule_id,
      scheduled_window_start_ms: toMs(row.scheduled_window_start),
      user_id: row.user_id,
      device_id: row.device_id,
      reserved_at_ms: toMs(row.reserved_at),
    })),
  });
});
