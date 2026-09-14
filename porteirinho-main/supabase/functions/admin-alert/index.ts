import { createClient } from 'npm:@supabase/supabase-js@2.57.4'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

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
  const alertId = String(body.alert_id || '')
  if (!alertId) return json({ error: 'ALERT_REQUIRED' }, 400)

  const now = new Date().toISOString()
  const { data, error } = await admin.from('alerts')
    .update({ resolved_at: now, resolved_by: user.id })
    .eq('id', alertId)
    .is('resolved_at', null)
    .select('id,resolved_at')
    .maybeSingle()
  if (error) return json({ error: 'ALERT_UPDATE_FAILED' }, 500)
  if (!data) {
    const { data: existing } = await admin.from('alerts').select('id,resolved_at').eq('id', alertId).maybeSingle()
    if (!existing) return json({ error: 'ALERT_NOT_FOUND' }, 404)
    return json({ ok: true, alert: existing })
  }

  await admin.from('audit_logs').insert({
    actor_type: 'ADMIN', actor_admin_id: user.id, action: 'ALERT_RESOLVED', entity_type: 'alert', entity_id: alertId,
    metadata: { resolved_at: now },
  })
  return json({ ok: true, alert: data })
})
