import { Banner, Button, Label, Select, Spinner, TextInput } from '@primer/react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { api, reason, type AgreementTemplates, type ClaimPreview, type DeadLetter, type Me, type QueueTask, type ToolStatus } from '../api/client'
import { Status, TOOL_NAME } from '../components/Status'
import { ToolName } from '../components/ToolIcon'
import { Mascot } from '../components/Mascot'
import { TopBar } from '../components/TopBar'
import { MemberDbBox } from './MemberDbBox'
import { Automations } from './Automations'

/**
 * National controls (build plan step 9): each tool's kill switch and dry run, the admin queue (changes made by
 * hand, removals held at the blast-radius limit) and dead letters. National admins only.
 */
export function National({ me }: { me: Me }) {
  const [tools, setTools] = useState<ToolStatus[] | null>(null)
  const [tasks, setTasks] = useState<QueueTask[]>([])
  const [dead, setDead] = useState<DeadLetter[]>([])
  const [error, setError] = useState<string | null>(null)
  const [requested, setRequested] = useState(false)

  useEffect(() => {
    void api.GET('/api/national/tools').then(({ data, response }) => (data ? setTools(data) : setError(response.status === 403 ? 'National admins only.' : 'Could not load.')))
    void api.GET('/api/national/queue').then(({ data }) => data && setTasks(data))
    void api.GET('/api/national/dead-letters').then(({ data }) => data && setDead(data))
  }, [])

  async function setTool(tool: string, enabled: boolean, dryRun: boolean, why: string | null) {
    const { data, error: failed, response } = await api.PUT('/api/national/tools/{tool}', { params: { path: { tool } }, body: { enabled, dryRun, reason: why } })
    if (!data) return setError(reason(failed, response))
    setError(null)
    setTools(data)
  }

  // A held-removals task says which run it showed, so a newer one can't be confirmed unseen (second review L4).
  async function decide(task: QueueTask, action: 'done' | 'cancel') {
    const { data, error: failed, response } = await api.POST('/api/national/queue/{id}', { params: { path: { id: task.id } }, body: { action, reason: null, runId: task.runId ?? null } })
    if (!data) return setError(reason(failed, response))
    setTasks(data)
  }

  async function retry(only: DeadLetter[]) {
    const { data, error: failed, response } = await api.POST('/api/national/dead-letters/retry', { body: only.map((d) => ({ personId: d.personId, resourceId: d.resourceId })) })
    if (!data) return setError(reason(failed, response))
    setDead(data)
  }

  async function runNow() {
    const { response } = await api.POST('/api/national/sync/run')
    if (response.status !== 202) return setError(reason(undefined, response, 'The sync could not be started'))
    setRequested(true)
  }

  const open = tasks.filter((t) => t.status === 'open')
  const recent = tasks.filter((t) => t.status !== 'open')

  return (
    <>
      <TopBar me={me} />
      <div className="page-head">
        <div className="inner">
          <div className="eyebrow">National</div>
          <h1 className="page-title">Sync and admin queue</h1>
        </div>
      </div>
      {!tools ? (
        <div className="center">{error ? <Banner title="Not available" description={error} variant="critical" /> : <Spinner aria-label="Loading" />}</div>
      ) : (
        <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
          {error && <Banner title="Not saved" description={error} variant="critical" onDismiss={() => setError(null)} />}

          <section className="box" aria-labelledby="national-projects-title">
            <div className="box-head" id="national-projects-title">National projects</div>
            <div className="box-row" style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
              <span>National’s own projects, with teams from any chapter.</span>
              <Button size="small" as={Link} to="/chapters/national/projects" style={{ marginLeft: 'auto' }}>Open national projects</Button>
            </div>
          </section>

          <section className="box" aria-labelledby="tools-title">
            <div className="box-head" id="tools-title">
              Tools
              <span style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
                {requested && <span className="muted">Sync requested</span>}
                <Button size="small" onClick={runNow}>Run sync now</Button>
              </span>
            </div>
            <ul className="adopt-list" aria-label="Tools">
              {tools.map((t) => <ToolRow key={t.tool} t={t} onSet={setTool} />)}
            </ul>
          </section>

          <section className="box" aria-labelledby="queue-title">
            <div className="box-head" id="queue-title">Admin queue <span className="Counter">{open.length}</span></div>
            {open.length === 0 && <div className="box-row muted mascot-row"><Mascot pose="nap" size={56} />Nothing to do.</div>}
            <ul className="adopt-list" aria-label="Open tasks">
              {open.map((t) => (
                <li key={t.id}>
                  <div className="task-row">
                    <div className="adopt-title">
                      <div className="adopt-name">
                        <span className="name-text">{t.description}</span>
                        {t.action === 'confirm_removals' && <Label variant="attention">Held</Label>}
                      </div>
                      <div className="adopt-meta">{TOOL_NAME[t.tool] ?? t.tool} · {new Date(t.createdAt).toLocaleString()}</div>
                    </div>
                    <span style={{ display: 'flex', gap: 8 }}>
                      <Button size="small" variant={t.action === 'confirm_removals' ? 'primary' : 'default'} onClick={() => decide(t, 'done')}>
                        {t.action === 'confirm_removals' ? 'Confirm removals' : 'Done'}
                      </Button>
                      <Button size="small" onClick={() => decide(t, 'cancel')}>Cancel</Button>
                    </span>
                  </div>
                </li>
              ))}
            </ul>
            {recent.length > 0 && (
              <details className="box-row">
                <summary className="muted">Closed in the last 7 days ({recent.length})</summary>
                <ul className="plain-list">
                  {recent.map((t) => (
                    <li key={t.id}>
                      <Status tone={t.status === 'done' ? 'ok' : 'off'}>{t.status === 'done' ? 'Done' : 'Cancelled'}</Status> {t.description}
                      <span className="muted">{t.doneBy ? ` · ${t.doneBy}` : ''}</span>
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </section>

          <Automations scope={{ kind: 'national' }} />

          <ClaimLinks me={me} />

          <AgreementTemplatesBox />

          <MemberDbBox />

          <section className="box" aria-labelledby="dead-title">
            <div className="box-head" id="dead-title">
              Dead letters <span className="Counter">{dead.length}</span>
              {dead.length > 1 && <Button size="small" style={{ marginLeft: 'auto' }} onClick={() => retry([])}>Retry all</Button>}
            </div>
            <div className="box-row muted">Changes that failed five times. They aren't tried again until you retry them.</div>
            <ul className="adopt-list" aria-label="Dead letters">
              {dead.map((d) => (
                <li key={`${d.personId}-${d.resourceId}`}>
                  <div className="task-row">
                    <div className="adopt-title">
                      <div className="adopt-name"><span className="name-text">{d.person}</span><span className="muted">{TOOL_NAME[d.tool] ?? d.tool} · {d.resource}</span></div>
                      <div className="adopt-meta">{d.attempts} attempts{d.lastError ? ` · ${d.lastError}` : ''}</div>
                    </div>
                    <Button size="small" onClick={() => retry([d])}>Retry</Button>
                  </div>
                </li>
              ))}
            </ul>
          </section>
        </div>
      )}
    </>
  )
}

/** One tool: state in words, and its two switches. Pausing asks why; leaving dry run asks to confirm. */
function ToolRow({ t, onSet }: { t: ToolStatus; onSet: (tool: string, enabled: boolean, dryRun: boolean, why: string | null) => void }) {
  const [pausing, setPausing] = useState(false)
  const [why, setWhy] = useState('')
  const [confirming, setConfirming] = useState(false)
  const name = TOOL_NAME[t.tool] ?? t.tool
  const [label, tone] = !t.enabled ? ['Paused', 'danger'] as const : t.dryRun ? ['Dry run', 'off'] as const : ['Changing the tool', 'ok'] as const
  return (
    <li>
      <div className="tool-row">
        <strong><ToolName tool={t.tool} /></strong>
        <div className="adopt-title">
          <div className="adopt-name">
            <Status tone={tone}>{label}</Status>
            {!t.writable && <span className="muted">read-only here (no write switch)</span>}
          </div>
          <div className="adopt-meta">
            {t.pauseReason && `Paused: ${t.pauseReason} · `}
            {t.lastRun
              ? `Last run ${t.lastRun.mode === 'apply' ? '' : '(dry run) '}${t.lastRun.status}, ${new Date(t.lastRun.ranAt).toLocaleString()}${t.lastRun.mode === 'apply' ? ` · ${t.lastRun.applied} applied, ${t.lastRun.removed} removed` : ''}${t.lastRun.error ? ` · ${t.lastRun.error}` : ''}`
              : 'Not run yet'}
          </div>
        </div>
        <span style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {t.enabled ? (
            pausing ? (
              <>
                <TextInput size="small" aria-label={`Why pause ${name}`} placeholder="Why?" value={why} onChange={(e) => setWhy(e.target.value)} />
                <Button size="small" variant="danger" disabled={!why.trim()} onClick={() => { onSet(t.tool, false, t.dryRun, why); setPausing(false) }}>Pause</Button>
                <Button size="small" onClick={() => setPausing(false)}>Cancel</Button>
              </>
            ) : <Button size="small" onClick={() => setPausing(true)}>Pause</Button>
          ) : <Button size="small" onClick={() => onSet(t.tool, true, t.dryRun, null)}>Resume</Button>}
          {t.dryRun ? (
            confirming ? (
              <>
                <Button size="small" variant="danger" onClick={() => { onSet(t.tool, t.enabled, false, t.pauseReason ?? null); setConfirming(false) }}>
                  Yes, let the portal change {name}
                </Button>
                <Button size="small" onClick={() => setConfirming(false)}>Cancel</Button>
              </>
            ) : <Button size="small" disabled={!t.writable} onClick={() => setConfirming(true)}>Leave dry run</Button>
          ) : <Button size="small" onClick={() => onSet(t.tool, t.enabled, true, t.pauseReason ?? null)}>Back to dry run</Button>}
        </span>
      </div>
    </li>
  )
}

/** PRD: national sends each imported record a single-use claim link (14 days) to every address on file. */
function ClaimLinks({ me }: { me: Me }) {
  const [chapter, setChapter] = useState('')
  const [preview, setPreview] = useState<ClaimPreview | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    setPreview(null)
    void api.GET('/api/national/claims/preview', { params: { query: chapter ? { chapter } : {} } }).then(({ data }) => data && setPreview(data))
  }, [chapter])

  async function send() {
    const { data, error: failed, response } = await api.POST('/api/national/claims/send', { body: { chapter: chapter || null, limit: 200 } })
    setConfirming(false)
    if (!data) return setError(reason(failed, response, 'Not sent'))
    setError(null)
    setPreview(data)
  }

  const batch = Math.min(preview?.eligible ?? 0, 200)
  return (
    <section className="box" aria-labelledby="claims-title">
      <div className="box-head" id="claims-title">Claim links</div>
      <div className="box-row muted">Imported members who haven’t claimed their record get a single-use link, valid 14 days, at every address on file. Unclaimed records become alumni a month after their first link.</div>
      {error && <div className="box-row"><Banner title="Not sent" description={error} variant="critical" /></div>}
      <div className="box-row inline-form">
        <Select aria-label="Chapter" value={chapter} onChange={(e) => { setChapter(e.target.value); setConfirming(false) }}>
          <Select.Option value="">All chapters</Select.Option>
          {me.chapters.map((c) => <Select.Option key={c.code} value={c.code}>{c.name}</Select.Option>)}
        </Select>
        {preview ? (
          <span className="muted">{preview.eligible} to send · {preview.alreadySent} with an open link · {preview.withoutEmail} without an address</span>
        ) : <Spinner size="small" aria-label="Counting" />}
        <span style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          {confirming ? (
            <>
              <Button variant="danger" onClick={() => void send()}>Yes, email {batch} {batch === 1 ? 'person' : 'people'}</Button>
              <Button onClick={() => setConfirming(false)}>Cancel</Button>
            </>
          ) : <Button disabled={!batch} onClick={() => setConfirming(true)}>Send {batch} claim {batch === 1 ? 'link' : 'links'}</Button>}
        </span>
      </div>
    </section>
  )
}

const AGREEMENT_KINDS: [string, string, string][] = [
  ['volunteer', 'Volunteer agreement', 'Signed while applying or claiming a record'],
  ['code_of_conduct', 'Code of conduct', 'Signed while applying or claiming a record'],
  ['project', 'Project agreement', 'Sent when someone joins a project; gated repos and vault collections wait for it'],
]

/**
 * Which Documenso templates agreements are made from (build plan step 11). Each kind has at most one active
 * template; replacing it makes the next version, and agreements already signed stay valid.
 */
function AgreementTemplatesBox() {
  const [view, setView] = useState<AgreementTemplates | null>(null)
  const [editing, setEditing] = useState<string | null>(null)
  const [form, setForm] = useState({ name: '', documensoTemplateId: '' })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { void api.GET('/api/national/agreement-templates').then(({ data }) => data && setView(data)) }, [])

  async function save(kind: string) {
    const { data, error: failed, response } = await api.POST('/api/national/agreement-templates', { body: { kind, ...form } })
    if (!data) return setError(reason(failed, response, 'Not saved'))
    setError(null)
    setEditing(null)
    setView(data)
  }

  async function turnOff(id: string) {
    const { data, error: failed, response } = await api.POST('/api/national/agreement-templates/{id}/deactivate', { params: { path: { id } } })
    if (!data) return setError(reason(failed, response, 'Not saved'))
    setView(data)
  }

  if (!view) return null
  const [state, tone] = !view.connected ? ['Documenso isn’t connected', 'off'] as const : view.sending ? ['Sending agreements', 'ok'] as const : ['Dry run: nothing is sent', 'off'] as const
  return (
    <section className="box" aria-labelledby="agreements-title">
      <div className="box-head" id="agreements-title">
        Agreements <span style={{ marginLeft: 'auto' }}><Status tone={tone}>{state}</Status></span>
      </div>
      <div className="box-row muted">
        Make each template in Documenso with one signer, then paste its ID or link here. Until a kind has a template, nobody is asked to sign it.
      </div>
      {error && <div className="box-row"><Banner title="Not saved" description={error} variant="critical" onDismiss={() => setError(null)} /></div>}
      <ul className="adopt-list" aria-label="Agreement templates">
        {AGREEMENT_KINDS.map(([kind, label, when]) => {
          const active = view.templates.find((t) => t.kind === kind && t.active)
          const older = view.templates.filter((t) => t.kind === kind && !t.active).length
          return (
            <li key={kind}>
              <div className="task-row">
                <div className="adopt-title">
                  <div className="adopt-name">
                    <span className="name-text">{label}</span>
                    {active ? <Status tone="ok">{active.name} · version {active.version}</Status> : <Status tone="off">Not set up</Status>}
                  </div>
                  <div className="adopt-meta">
                    {when}{active ? ` · ${active.sent} sent · Documenso ${active.documensoTemplateId}` : ''}{older ? ` · ${older} earlier ${older === 1 ? 'version' : 'versions'}` : ''}
                  </div>
                </div>
                {editing !== kind && (
                  <span style={{ display: 'flex', gap: 8 }}>
                    <Button size="small" onClick={() => { setEditing(kind); setForm({ name: active?.name ?? label, documensoTemplateId: '' }) }}>{active ? 'Replace' : 'Set up'}</Button>
                    {active && <Button size="small" variant="invisible" onClick={() => void turnOff(active.id)}>Turn off</Button>}
                  </span>
                )}
              </div>
              {editing === kind && (
                <div className="inline-form" style={{ padding: '0 16px 12px' }} role="group" aria-label={`${label} template`}>
                  <TextInput aria-label="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder={label} />
                  <TextInput aria-label="Documenso template" value={form.documensoTemplateId} onChange={(e) => setForm({ ...form, documensoTemplateId: e.target.value })}
                    placeholder="Template ID or link" style={{ minWidth: 280 }} />
                  <Button size="small" variant="primary" disabled={!form.name.trim() || !form.documensoTemplateId.trim()} onClick={() => void save(kind)}>
                    {active ? `Save as version ${active.version + 1}` : 'Save'}
                  </Button>
                  <Button size="small" onClick={() => setEditing(null)}>Cancel</Button>
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
