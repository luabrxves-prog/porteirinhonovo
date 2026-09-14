import { createClient } from "npm:@supabase/supabase-js@2.57.4";
import * as XLSX from "npm:xlsx@0.18.5";

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

const formatDate = (value: string | null) => value ? new Date(value).toLocaleString("pt-BR", { timeZone: "America/Sao_Paulo" }) : "";

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
    .select("id,organization_id,active,archived_at,api_token_hash,can_admin")
    .eq("public_id", publicId)
    .maybeSingle<Device>();

  if (deviceError) return json({ error: "device_lookup_failed" }, 503);
  if (!device || !device.active || device.archived_at) return json({ error: "device_not_authorized" }, 403);
  if ((await sha256(token)) !== device.api_token_hash) return json({ error: "device_token_invalid" }, 403);
  if (!device.can_admin) return json({ error: "admin_device_required" }, 403);

  const url = new URL(request.url);
  const days = Number(url.searchParams.get("days") ?? "30");
  if (![30, 90, 120].includes(days)) return json({ error: "invalid_period" }, 422);

  const cutoff = new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString();
  const { data, error } = await supabase
    .from("patrol_report_rows")
    .select("*")
    .eq("organization_id", device.organization_id)
    .gte("started_at_device", cutoff)
    .order("started_at_device", { ascending: false });

  if (error) return json({ error: "report_query_failed", detail: error.code }, 503);

  const rows = (data ?? []).map((row) => ({
    Data: formatDate(row.started_at_device),
    Ronda: row.patrol_name,
    Porteiro: row.gatekeeper_name,
    "Horário previsto": formatDate(row.scheduled_window_start),
    "Início real": formatDate(row.started_at_device),
    "Fim real": formatDate(row.ended_at_device),
    "Duração (min)": row.duration_seconds == null ? "" : Math.round(Number(row.duration_seconds) / 60),
    "Pontos concluídos": Number(row.checkpoints_completed ?? 0),
    "Pontos esperados": Number(row.checkpoints_expected ?? 0),
    "Leituras rápidas/suspeitas": Number(row.suspicious_scans ?? 0),
    Observações: Number(row.observations ?? 0),
    Alertas: Number(row.alerts ?? 0),
    "Alertas pendentes": Number(row.unresolved_alerts ?? 0),
    Status: row.status,
  }));

  const workbook = XLSX.utils.book_new();
  const worksheet = XLSX.utils.json_to_sheet(rows.length > 0 ? rows : [{ Mensagem: `Nenhuma ronda encontrada nos últimos ${days} dias.` }]);
  XLSX.utils.book_append_sheet(workbook, worksheet, "Rondas");

  const bytes = XLSX.write(workbook, { type: "array", bookType: "xlsx" }) as ArrayBuffer;
  const filename = `porteirinho-rondas-${days}-dias.xlsx`;

  return new Response(bytes, {
    status: 200,
    headers: {
      "content-type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "content-disposition": `attachment; filename=\"${filename}\"`,
      "cache-control": "no-store",
    },
  });
});
