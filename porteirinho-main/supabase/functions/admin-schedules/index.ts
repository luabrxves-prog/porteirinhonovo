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
  const templateId = String(body.patrol_template_id || '')
  const name = String(body.name || '').trim()
  const startMinute = Number(body.start_minute)
  if (!templateId || !name || !Number.isInteger(startMinute) || startMinute < 0 || startMinute > 1439) {
    return json({ error: 'INVALID_FIELDS' }, 400)
  }

  const { data: template, error: templateError } = await admin.from('patrol_templates')
    .select('id,name,system_fixed,active')
    .eq('id', templateId).maybeSingle()
  if (templateError) return json({ error: 'TEMPLATE_LOOKUP_FAILED' }, 500)
  if (!template || !template.active || !template.system_fixed) return json({ error: 'FIXED_PATROL_NOT_FOUND' }, 404)

  const { data: windows, error: windowsError } = await admin.from('patrol_schedule_windows')
    .select('id,start_time,end_time,version')
    .eq('patrol_template_id', templateId).eq('active', true)
  if (windowsError || !windows?.length) return json({ error: 'FIXED_PATROL_HAS_NO_WINDOWS' }, 409)

  const first = windows[0]
  const [sh, sm] = String(first.start_time).split(':').map(Number)
  const [eh, em] = String(first.end_time).split(':').map(Number)
  const oldStart = sh * 60 + sm
  const oldEnd = eh * 60 + em
  const duration = (oldEnd - oldStart + 1440) % 1440 || 60
  const endMinute = (startMinute + duration) % 1440
  const hhmmss = (minute: number) => `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}:00`
  const expectedVersions = Object.fromEntries(windows.map((w: any) => [w.id, w.version]))

  const { error: timeError } = await userClient.rpc('admin_update_fixed_patrol_times', {
    p_template_id: templateId,
    p_start_time: hhmmss(startMinute),
    p_end_time: hhmmss(endMinute),
    p_expected_versions: expectedVersions,
  })
  if (timeError) return json({ error: String(timeError.message || 'SCHEDULE_UPDATE_FAILED') }, 409)

  const { error: nameError } = await admin.from('patrol_templates').update({ name }).eq('id', templateId).eq('system_fixed', true)
  if (nameError) return json({ error: 'NAME_UPDATE_FAILED' }, 500)

  await admin.from('audit_logs').insert({
    actor_type: 'ADMIN', actor_admin_id: user.id, action: 'FIXED_PATROL_UPDATED', entity_type: 'patrol_template', entity_id: templateId,
    metadata: { name, start_minute: startMinute, end_minute: endMinute },
  })

  return json({ ok: true, patrol_template_id: templateId, name, start_minute: startMinute, end_minute: endMinute })
})
