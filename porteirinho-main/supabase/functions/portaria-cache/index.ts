import { createClient } from 'npm:@supabase/supabase-js@2.57.4'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { 'Content-Type': 'application/json' },
})
const toHex = (bytes: Uint8Array) => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
async function sha256(value: string) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
  return toHex(new Uint8Array(digest))
}

Deno.serve(async (req: Request) => {
  if (req.method !== 'POST') return json({ error: 'METHOD_NOT_ALLOWED' }, 405)

  const url = Deno.env.get('SUPABASE_URL')!
  const secret = JSON.parse(Deno.env.get('SUPABASE_SECRET_KEYS') || '{}').default || Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')
  if (!secret) return json({ error: 'SERVER_CONFIG_ERROR' }, 500)
  const db = createClient(url, secret, { auth: { persistSession: false, autoRefreshToken: false } })

  const deviceId = req.headers.get('x-device-id') || ''
  const deviceSecret = req.headers.get('x-device-secret') || ''
  if (!deviceId || !deviceSecret) return json({ error: 'DEVICE_AUTH_REQUIRED' }, 401)

  const { data: device, error: deviceError } = await db.from('devices').select('id,status,building_id').eq('id', deviceId).maybeSingle()
  if (deviceError) return json({ error: deviceError.message }, 500)
  if (!device || device.status !== 'ACTIVE' || !device.building_id) return json({ error: 'DEVICE_NOT_ACTIVE' }, 403)

  const { data: credential, error: credentialError } = await db.from('device_credentials').select('secret_hash').eq('device_id', deviceId).maybeSingle()
  if (credentialError) return json({ error: credentialError.message }, 500)
  if (!credential || credential.secret_hash !== await sha256(deviceSecret)) return json({ error: 'INVALID_DEVICE_SECRET' }, 401)

  const buildingId = device.building_id
  const { data: buildingDevices, error: buildingDevicesError } = await db.from('devices').select('id').eq('building_id', buildingId)
  if (buildingDevicesError) return json({ error: buildingDevicesError.message }, 500)
  const deviceIds = (buildingDevices || []).map((x: any) => x.id)
  const cutoff = new Date(Date.now() - 120 * 24 * 60 * 60_000).toISOString()

  const [buildingResult, guardsResult, patrolsResult, windowsResult, assignmentsResult, checkpointLinksResult, checkpointsResult, qrsResult, alertsResult] = await Promise.all([
    db.from('buildings').select('id,name,timezone').eq('id', buildingId).eq('active', true).maybeSingle(),
    db.from('guards').select('id,name,photo_url,pin_state,active,guard_credentials(pin_hash,pin_salt,iterations,must_change_pin,credential_version)').eq('active', true).is('e2e_run_id', null).order('name'),
    db.from('patrol_templates').select('id,building_id,name,description,system_fixed').eq('building_id', buildingId).eq('active', true).eq('system_fixed', true).order('created_at'),
    db.from('patrol_schedule_windows').select('id,patrol_template_id,day_of_week,start_time,end_time,late_tolerance_minutes,version').eq('active', true),
    db.from('patrol_schedule_assignments').select('schedule_window_id,guard_id').eq('active', true),
    db.from('patrol_template_checkpoints').select('patrol_template_id,checkpoint_id,required').eq('active', true),
    db.from('checkpoints').select('id,floor_id,name,sort_order,active,system_fixed,floors!inner(id,name,sort_order,active,blocks!inner(id,name,sort_order,building_id,active))').eq('active', true).eq('floors.active', true).eq('floors.blocks.active', true).eq('floors.blocks.building_id', buildingId).order('sort_order'),
    db.from('qr_tokens').select('id,checkpoint_id,version,status,created_at,qr_token_secrets!inner(token_hash)').eq('status', 'ACTIVE'),
    deviceIds.length
      ? db.from('alerts').select('id,alert_type,message,guard_id,patrol_run_id,device_id,created_at,resolved_at').in('device_id', deviceIds).gte('created_at', cutoff).order('created_at', { ascending: false }).limit(500)
      : Promise.resolve({ data: [], error: null }),
  ])

  const errors = [buildingResult.error, guardsResult.error, patrolsResult.error, windowsResult.error, assignmentsResult.error, checkpointLinksResult.error, checkpointsResult.error, qrsResult.error, alertsResult.error].filter(Boolean)
  if (errors.length > 0) return json({ error: errors[0]?.message || 'CACHE_QUERY_FAILED' }, 500)
  if (!buildingResult.data) return json({ error: 'BUILDING_NOT_ACTIVE' }, 409)

  const patrols = patrolsResult.data || []
  const patrolIds = new Set(patrols.map((x: any) => x.id))
  const checkpointIds = new Set((checkpointsResult.data || []).map((x: any) => x.id))
  const windows = (windowsResult.data || []).filter((x: any) => patrolIds.has(x.patrol_template_id))
  const windowIds = new Set(windows.map((x: any) => x.id))
  const assignments = (assignmentsResult.data || []).filter((x: any) => windowIds.has(x.schedule_window_id))
  const checkpointLinks = (checkpointLinksResult.data || []).filter((x: any) => patrolIds.has(x.patrol_template_id) && checkpointIds.has(x.checkpoint_id))
  const qrs = (qrsResult.data || []).filter((x: any) => checkpointIds.has(x.checkpoint_id)).map((x: any) => ({
    qr_token_id: x.id,
    checkpoint_id: x.checkpoint_id,
    version: x.version,
    issued_at: x.created_at,
    token_hash: Array.isArray(x.qr_token_secrets) ? x.qr_token_secrets[0]?.token_hash : x.qr_token_secrets?.token_hash,
  })).filter((x: any) => Boolean(x.token_hash))

  const guards = (guardsResult.data || []).map((g: any) => {
    const c = Array.isArray(g.guard_credentials) ? g.guard_credentials[0] : g.guard_credentials
    return {
      id: g.id,
      name: g.name,
      photo_url: g.photo_url,
      pin_state: g.pin_state,
      credential: c ? {
        pin_hash: c.pin_hash,
        pin_salt: c.pin_salt,
        iterations: c.iterations,
        must_change_pin: c.must_change_pin,
        credential_version: c.credential_version,
      } : null,
    }
  }).filter((g: any) => Boolean(g.credential))

  const alerts = (alertsResult.data || []).map((a: any) => ({
    id: a.id,
    type: a.alert_type,
    description: a.message,
    guard_id: a.guard_id,
    patrol_run_id: a.patrol_run_id,
    device_id: a.device_id,
    created_at: a.created_at,
    resolved_at: a.resolved_at,
  }))

  await db.from('devices').update({ last_sync_at: new Date().toISOString() }).eq('id', deviceId)
  const { data: syncState } = await db.from('client_sync_state').select('version').eq('id', 1).maybeSingle()

  return json({
    generated_at: new Date().toISOString(),
    sync_version: syncState?.version ?? 0,
    building: buildingResult.data,
    guards,
    patrols,
    windows,
    assignments,
    checkpoints: checkpointsResult.data || [],
    patrol_checkpoints: checkpointLinks,
    qr_tokens: qrs,
    alerts,
  })
})
