import { Banner, Button, Label, Spinner, TextInput } from '@primer/react'
import { useEffect, useState } from 'react'
import { api, reason, type DeadLetter, type Me, type QueueTask, type ToolStatus } from '../api/client'
import { Status, TOOL_NAME } from '../components/Status'
import { TopBar } from '../components/TopBar'

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

  async function decide(id: string, action: 'done' | 'cancel') {
    const { data, error: failed, response } = await api.POST('/api/national/queue/{id}', { params: { path: { id } }, body: { action, reason: null } })
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
            {open.length === 0 && <div className="box-row muted">Nothing to do.</div>}
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
                      <Button size="small" variant={t.action === 'confirm_removals' ? 'primary' : 'default'} onClick={() => decide(t.id, 'done')}>
                        {t.action === 'confirm_removals' ? 'Confirm removals' : 'Done'}
                      </Button>
                      <Button size="small" onClick={() => decide(t.id, 'cancel')}>Cancel</Button>
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
        <strong>{name}</strong>
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
