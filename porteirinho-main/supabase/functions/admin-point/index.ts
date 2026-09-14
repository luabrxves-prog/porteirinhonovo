import { createClient } from 'npm:@supabase/supabase-js@2.57.4'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const toHex = (bytes: Uint8Array) => Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
async function sha256(value: string) { const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)); return toHex(new Uint8Array(digest)) }

Deno.serve(async (req: Request) => {
  if (req.method !== 'POST') return json({ error: 'METHOD_NOT_ALLOWED' }, 405)
  const auth = req.headers.get('Authorization')
  if (!auth?.startsWith('Bearer ')) return json({ error: 'UNAUTHORIZED' }, 401)

  const url = Deno.env.get('SUPABASE_URL')!
  const publishable = JSON.parse(Deno.env.get('SUPABASE_PUBLISHABLE_KEYS') || '{}').default
  const secret = JSON.parse(Deno.env.get('SUPABASE_SECRET_KEYS') || '{}').default
  if (!url || !publishable || !secret) return json({ error: 'SERVER_CONFIG_ERROR' }, 500)

  const userClient = createClient(url, publishable, { global: { headers: { Authorization: auth } } })
  const admin = createClient(url, secret)
  const token = auth.slice(7)
  const { data: userData, error: userError } = await userClient.auth.getUser(token)
  const user = userData.user
  if (userError || !user) return json({ error: 'UNAUTHORIZED' }, 401)
  if (user.app_metadata?.role !== 'admin') return json({ error: 'FORBIDDEN' }, 403)

  const body = await req.json().catch(() => ({}))
  const name = String(body.name || '').trim()
  if (name.length < 2 || name.length > 100) return json({ error: 'INVALID_NAME' }, 400)

  const randomBytes = crypto.getRandomValues(new Uint8Array(32))
  const randomToken = toHex(randomBytes)
  const tokenValue = `rondasafe:v1:${randomToken}`
  const tokenHash = await sha256(tokenValue)
  const fingerprint = tokenHash.slice(0, 12).toUpperCase()

  const { data, error } = await admin.rpc('create_extra_checkpoint_with_qr', {
    p_name: name,
    p_actor_id: user.id,
    p_token_value: tokenValue,
    p_token_hash: tokenHash,
    p_fingerprint: fingerprint,
  })
  if (error) {
    const message = String(error.message || '')
    if (message.includes('PATROL_ACTIVE')) return json({ error: 'PATROL_ACTIVE' }, 409)
    return json({ error: 'CHECKPOINT_CREATE_FAILED', message }, 500)
  }

  const result = data?.[0]
  return json({
    checkpoint_id: result?.checkpoint_id,
    qr_token_id: result?.qr_token_id,
    qr_version: result?.qr_version,
    token_value: tokenValue,
  })
})
