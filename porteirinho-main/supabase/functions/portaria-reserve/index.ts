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
  if (!url || !secret) return json({ error: 'SERVER_CONFIG_ERROR' }, 500)

  const deviceId = req.headers.get('x-device-id') || ''
  const deviceSecret = req.headers.get('x-device-secret') || ''
  if (!deviceId || !deviceSecret) return json({ error: 'DEVICE_AUTH_REQUIRED' }, 401)

  const db = createClient(url, secret, { auth: { persistSession: false, autoRefreshToken: false } })
  const { data: device, error: deviceError } = await db
    .from('devices')
    .select('id,status,building_id')
    .eq('id', deviceId)
    .maybeSingle()
  if (deviceError) return json({ error: 'DEVICE_LOOKUP_FAILED' }, 503)
  if (!device || device.status !== 'ACTIVE' || !device.building_id) return json({ error: 'DEVICE_NOT_ACTIVE' }, 403)

  const { data: credential, error: credentialError } = await db
    .from('device_credentials')
    .select('secret_hash')
    .eq('device_id', deviceId)
    .maybeSingle()
  if (credentialError) return json({ error: 'DEVICE_CREDENTIAL_LOOKUP_FAILED' }, 503)
  if (!credential || credential.secret_hash !== await sha256(deviceSecret)) return json({ error: 'INVALID_DEVICE_SECRET' }, 401)

  const body = await req.json().catch(() => ({}))
  const guardId = String(body.guard_id || '')
  const patrolTemplateId = String(body.patrol_template_id || '')
  if (!guardId || !patrolTemplateId) return json({ error: 'MISSING_FIELDS' }, 400)

  const { data, error } = await db.rpc('reserve_current_patrol_occurrence', {
    p_device_id: deviceId,
    p_guard_id: guardId,
    p_patrol_template_id: patrolTemplateId,
  })

  if (error) {
    const message = String(error.message || '')
    if (message.includes('PATROL_RESERVED_BY_ANOTHER_GATEKEEPER') || message.includes('PATROL_OCCURRENCE_ALREADY_EXECUTED')) {
      return json({ error: 'PATROL_RESERVED_BY_ANOTHER_GATEKEEPER' }, 409)
    }
    if (message.includes('PATROL_NOT_AVAILABLE')) return json({ error: 'PATROL_NOT_AVAILABLE' }, 409)
    return json({ error: 'RESERVATION_FAILED' }, 500)
  }

  return json({ reservation: data?.[0] ?? null })
})
