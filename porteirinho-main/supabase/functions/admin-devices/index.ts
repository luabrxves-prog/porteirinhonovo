import { createClient } from 'npm:@supabase/supabase-js@2.57.4'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const toHex = (bytes: Uint8Array) => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
async function sha256(value: string) { const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)); return toHex(new Uint8Array(digest)) }
function randomSecret() { return toHex(crypto.getRandomValues(new Uint8Array(32))) }

Deno.serve(async (req: Request) => {
  if (req.method !== 'POST') return json({ error: 'METHOD_NOT_ALLOWED' }, 405)
  const authHeader = req.headers.get('Authorization')
  if (!authHeader?.startsWith('Bearer ')) return json({ error: 'UNAUTHORIZED' }, 401)

  const url = Deno.env.get('SUPABASE_URL')!
  const publishable = JSON.parse(Deno.env.get('SUPABASE_PUBLISHABLE_KEYS') || '{}').default
  const secret = JSON.parse(Deno.env.get('SUPABASE_SECRET_KEYS') || '{}').default
  if (!publishable || !secret) return json({ error: 'SERVER_CONFIG_ERROR' }, 500)

  const userClient = createClient(url, publishable, { global: { headers: { Authorization: authHeader } } })
  const adminClient = createClient(url, secret)
  const token = authHeader.slice(7)
  const { data: userData, error: userError } = await userClient.auth.getUser(token)
  const user = userData.user
  if (userError || !user) return json({ error: 'UNAUTHORIZED' }, 401)
  if (user.app_metadata?.role !== 'admin') return json({ error: 'FORBIDDEN' }, 403)

  const body = await req.json().catch(() => ({}))
  const action = String(body.action || '')
  if (action !== 'provision_portaria' && action !== 'provision_portaria_default') return json({ error: 'INVALID_ACTION' }, 400)

  const installationId = String(body.installation_id || '')
  const name = String(body.name || '').trim()
  if (!installationId || !name) return json({ error: 'MISSING_FIELDS' }, 400)

  let buildingId = String(body.building_id || '')
  if (action === 'provision_portaria_default') {
    const { data: buildings, error: buildingError } = await adminClient.from('buildings')
      .select('id,name,operations_started_at,created_at')
      .eq('active', true)
      .is('archived_at', null)
      .is('e2e_run_id', null)
      .order('operations_started_at', { ascending: false, nullsFirst: false })
      .order('created_at', { ascending: true })
      .limit(2)
    if (buildingError) return json({ error: 'BUILDING_LOOKUP_FAILED' }, 500)
    if (!buildings?.length) return json({ error: 'ACTIVE_BUILDING_NOT_FOUND' }, 409)
    buildingId = buildings[0].id
  }
  if (!buildingId) return json({ error: 'BUILDING_REQUIRED' }, 400)

  const deviceSecret = randomSecret()
  const secretHash = await sha256(deviceSecret)
  const { data, error } = await adminClient.rpc('provision_portaria_device', {
    p_installation_id: installationId,
    p_building_id: buildingId,
    p_name: name,
    p_model: body.model ? String(body.model) : '',
    p_android_version: body.android_version ? String(body.android_version) : '',
    p_app_version: body.app_version ? String(body.app_version) : '',
    p_actor_id: user.id,
    p_secret_hash: secretHash,
  })
  if (error) return json({ error: String(error.message || 'PROVISION_FAILED') }, 400)
  return json({ device: data?.[0] ?? null, device_secret: deviceSecret })
})
