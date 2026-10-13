import { PlusIcon } from '@primer/octicons-react'
import { Banner, Button, Spinner, TextInput } from '@primer/react'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { api, reason, type DraftDetail, type DraftPreview, type DraftSummary } from '../api/client'
import { setActiveDraft, useActiveDraft } from '../api/drafts'
import { Status, TOOL_NAME } from '../components/Status'
import { ToolIcon } from '../components/ToolIcon'
import { resourceName, toolByLabel } from '../api/tools'

const when = (d?: string | null) => (d ? new Date(d).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '')

/** A draft's state in words. */
function DraftState({ d }: { d: DraftSummary }) {
  if (d.status === 'scheduled') return <Status tone="off">Releases {when(d.scheduledFor)}</Status>
  if (d.status === 'released') return <Status tone="ok">Released {when(d.releasedAt)}{d.releasedBy ? ` by ${d.releasedBy}` : ''}</Status>
  if (d.status === 'discarded') return <Status tone="off">Discarded</Status>
  return <Status tone="off">Draft</Status>
}


type Refs = Record<string, { tool: string; name: string }>

/**
 * A resource as the preview names it ("umd-rise-dc (Slack)", "… (GitHub), now admin"), as a chip with its tool's icon.
 * The tool and name come from the preview's [refs]; summaries stored before it had refs fall back to reading the label.
 */
function ResourceChip({ label, refs, remove }: { label: string; refs?: Refs; remove?: boolean }) {
  const now = label.match(/, now (\w+)$/)?.[1]
  const base = now ? label.slice(0, -`, now ${now}`.length) : label
  const parsed = base.match(/^(.*) \(([^)]+)\)$/)
  const ref = refs?.[base] ?? (parsed && toolByLabel(parsed[2]!) ? { tool: toolByLabel(parsed[2]!)!, name: parsed[1]! } : undefined)
  if (!ref) return <span className="chip">{label}</span>
  return (
    <span className={`chip${remove ? ' chip-remove' : ''}`} title={`${TOOL_NAME[ref.tool] ?? ref.tool}: ${ref.name}`}>
      <ToolIcon tool={ref.tool} size={14} label />{resourceName(ref.tool, ref.name)}{now && <span className="muted"> · now {now}</span>}
    </span>
  )
}

/** "+2 · 2 after agreement · −1": one person's change at a glance. */
function summary(p: DraftPreview['perPerson'][number]) {
  return [
    p.gains.length && `+${p.gains.length}`, p.afterAgreement.length && `${p.afterAgreement.length} after agreement`, p.loses.length && `−${p.loses.length}`,
  ].filter(Boolean).join(' · ')
}

/** Each person's change: projects joined and left, resources gained, waiting on an agreement, and lost. */
export function PeopleChanges({ people, label = 'People' }: { people: DraftPreview['perPerson']; label?: string }) {
  return (
    <ul className="adopt-list" aria-label={label}>
      {people.map((p) => (
        <li key={p.personId}>
          <div className="person-change">
            <div className="person-change-head">
              <span className="initials small" aria-hidden="true">{p.name.split(/\s+/).slice(0, 2).map((w) => w[0]).join('').toUpperCase()}</span>
              <span className="name-text">{p.name}</span>
              <span className="muted person-change-sum">{summary(p)}</span>
            </div>
            <dl className="change-grid">
              {p.joins.length > 0 && <><dt>Joins</dt><dd>{p.joins.map((j) => <span key={j} className="chip">{j}</span>)}</dd></>}
              {p.leaves.length > 0 && <><dt>Leaves</dt><dd>{p.leaves.map((j) => <span key={j} className="chip">{j}</span>)}</dd></>}
              {p.gains.length > 0 && <><dt className="add">Gets</dt><dd>{p.gains.map((g) => <ResourceChip key={g} label={g} refs={p.resources} />)}</dd></>}
              {p.afterAgreement.length > 0 && <><dt>After agreement</dt><dd>{p.afterAgreement.map((g) => <ResourceChip key={g} label={g} refs={p.resources} />)}</dd></>}
              {p.loses.length > 0 && <><dt className="remove">Loses</dt><dd>{p.loses.map((g) => <ResourceChip key={g} label={g} refs={p.resources} remove />)}</dd></>}
            </dl>
          </div>
        </li>
      ))}
    </ul>
  )
}

/** PRD: "Adds 34 people to 12 resources, removes 6". */
export function previewSentence(p: DraftPreview) {
  if (p.adds + p.removes + p.accessChanges + p.afterAgreement === 0) return p.people ? `Changes projects for ${p.people} ${p.people === 1 ? 'person' : 'people'}; no access changes` : 'No access changes'
  const who = `${p.people} ${p.people === 1 ? 'person' : 'people'}`
  if (p.adds + p.afterAgreement === 0) {
    // Nothing new for anyone: access levels change, or access is taken back.
    const parts = p.accessChanges ? [`Changes access for ${who} on ${p.resources} ${p.resources === 1 ? 'resource' : 'resources'}`] : []
    if (p.removes) parts.push(parts.length ? `removes ${p.removes}` : `Removes ${p.removes} ${p.removes === 1 ? 'grant' : 'grants'} from ${who}`)
    return parts.join(', ')
  }
  const parts = [`Adds ${p.people} ${p.people === 1 ? 'person' : 'people'} to ${p.resources} ${p.resources === 1 ? 'resource' : 'resources'}`]
  if (p.removes) parts.push(`removes ${p.removes}`)
  if (p.accessChanges) parts.push(`changes ${p.accessChanges}`)
  return parts.join(', ') + (p.afterAgreement ? ` · ${p.afterAgreement} more after the project agreement` : '')
}

/**
 * The chapter's drafts (build plan step 12): plan many changes, check what they'd do, release them together, now or
 * at a set time. Leads, co-leads and national only.
 */
export function Drafts({ code, id }: { code: string; id?: string }) {
  return id ? <DraftView code={code} id={id} /> : <DraftList code={code} />
}

function DraftList({ code }: { code: string }) {
  const [drafts, setDrafts] = useState<DraftSummary[] | null>(null)
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()
  useEffect(() => { void api.GET('/api/chapters/{code}/drafts', { params: { path: { code } } }).then(({ data }) => setDrafts(data ?? [])) }, [code])

  async function create(e: React.FormEvent) {
    e.preventDefault()
    const { data, error: failed, response } = await api.POST('/api/chapters/{code}/drafts', { params: { path: { code } }, body: { name } })
    if (!data) return setError(reason(failed, response, 'Not created'))
    setActiveDraft(code, data.id)
    navigate(`/chapters/${code}/projects`)
  }

  if (!drafts) return <div className="center"><Spinner aria-label="Loading drafts" /></div>
  const open = drafts.filter((d) => d.status === 'draft' || d.status === 'scheduled')
  const past = drafts.filter((d) => d.status === 'released' || d.status === 'discarded')
  return (
    <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
      <section className="box" aria-labelledby="drafts-title">
        <div className="box-head" id="drafts-title">Drafts</div>
        <div className="box-row muted">
          Plan many changes, like next semester’s projects and teams, and release them together. Nobody sees a draft or gets access from it until it’s released.
        </div>
        {error && <div className="box-row"><Banner title="Not created" description={error} variant="critical" /></div>}
        <form className="box-row inline-form" onSubmit={create}>
          <TextInput aria-label="Draft name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Fall 2026 teams" />
          <Button type="submit" variant="primary" leadingVisual={PlusIcon} disabled={!name.trim()}>Start a draft</Button>
        </form>
        <ul className="adopt-list" aria-label="Open drafts">
          {open.map((d) => <DraftRow key={d.id} code={code} d={d} />)}
        </ul>
        {open.length === 0 && <div className="box-row muted">No open drafts.</div>}
        {past.length > 0 && (
          <details className="box-row">
            <summary className="muted">Released or discarded in the last 60 days ({past.length})</summary>
            <ul className="adopt-list" aria-label="Past drafts">{past.map((d) => <DraftRow key={d.id} code={code} d={d} />)}</ul>
          </details>
        )}
      </section>
    </div>
  )
}

function DraftRow({ code, d }: { code: string; d: DraftSummary }) {
  return (
    <li>
      <div className="task-row">
        <div className="adopt-title">
          <div className="adopt-name"><Link className="name-text" to={`/chapters/${code}/drafts/${d.id}`}>{d.name}</Link><DraftState d={d} /></div>
          <div className="adopt-meta">
            {d.changes} {d.changes === 1 ? 'change' : 'changes'}{d.createdBy ? ` · started by ${d.createdBy}` : ''} · {when(d.createdAt)}
            {d.releaseError ? ` · not released: ${d.releaseError}` : ''}
          </div>
        </div>
      </div>
    </li>
  )
}

function DraftView({ code, id }: { code: string; id: string }) {
  const [detail, setDetail] = useState<DraftDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState<'release' | 'discard' | null>(null)
  const [at, setAt] = useState('')
  const [released, setReleased] = useState(false)
  const [active] = useActiveDraft(code)
  const navigate = useNavigate()
  const path = { params: { path: { code, id } } }
  useEffect(() => { void api.GET('/api/chapters/{code}/drafts/{id}', path).then(({ data }) => data && setDetail(data)) }, [code, id])

  async function act(call: Promise<{ data?: DraftDetail; error?: unknown; response: Response }>) {
    const { data, error: failed, response } = await call
    setConfirming(null)
    if (!data) return setError(reason(failed, response, 'Not done'))
    setError(null)
    setDetail(data)
  }

  async function release() {
    const { data, error: failed, response } = await api.POST('/api/chapters/{code}/drafts/{id}/release', path)
    setConfirming(null)
    if (!data) return setError(reason(failed, response, 'Not released'))
    if (!data.released) {
      setError('Nothing was released: some changes no longer work. Fix them below, then try again.')
    } else {
      setReleased(true)
      if (active === id) setActiveDraft(code, null)
    }
    void api.GET('/api/chapters/{code}/drafts/{id}', path).then(({ data: d }) => d && setDetail(d))
  }

  if (!detail) return <div className="center"><Spinner aria-label="Loading draft" /></div>
  const { draft, changes, conflicts, preview } = detail
  const open = draft.status === 'draft' || draft.status === 'scheduled'
  const conflicted = new Set(conflicts.map((c) => c.changeId))
  return (
    <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
      <div><Link to={`/chapters/${code}/drafts`}>← Drafts</Link></div>
      {error && <Banner title="Not done" description={error} variant="critical" onDismiss={() => setError(null)} />}
      {released && <Banner title="Released" description="Every change applied together. The tools follow within the hour, and each member got a summary of what changed for them." variant="success" />}
      {draft.releaseError && draft.status === 'draft' && <Banner title="The scheduled release didn’t happen" description={draft.releaseError} variant="warning" />}

      <section className="box" aria-labelledby="draft-title">
        <div className="box-head" id="draft-title">{draft.name} <span style={{ marginLeft: 'auto' }}><DraftState d={draft} /></span></div>
        <div className="box-row">
          <strong>{previewSentence(preview)}</strong>
          {open && conflicts.length > 0 && <div className="muted">{conflicts.length} {conflicts.length === 1 ? 'change no longer works' : 'changes no longer work'}: fix {conflicts.length === 1 ? 'it' : 'them'} before release.</div>}
        </div>
        {detail.canEdit && (
          <div className="box-row" style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <Button onClick={() => { setActiveDraft(code, id); navigate(`/chapters/${code}/projects`) }}>{active === id ? 'Back to planning' : 'Plan changes in this draft'}</Button>
            {confirming === 'release' ? (
              <>
                <Button variant="primary" onClick={() => void release()}>Yes, release {changes.length} {changes.length === 1 ? 'change' : 'changes'} now</Button>
                <Button onClick={() => setConfirming(null)}>Cancel</Button>
              </>
            ) : <Button variant="primary" disabled={!changes.length || conflicts.length > 0} onClick={() => setConfirming('release')}>Release now</Button>}
            <TextInput type="datetime-local" aria-label="Release at" value={at} onChange={(e) => setAt(e.target.value)} />
            <Button disabled={!at || !changes.length || conflicts.length > 0}
              onClick={() => void act(api.POST('/api/chapters/{code}/drafts/{id}/schedule', { ...path, body: { at: new Date(at).toISOString() } }))}>Schedule</Button>
            <span style={{ marginLeft: 'auto' }}>
              {confirming === 'discard' ? (
                <>
                  <Button variant="danger" onClick={() => { if (active === id) setActiveDraft(code, null); void act(api.POST('/api/chapters/{code}/drafts/{id}/discard', path)) }}>Yes, discard it</Button>
                  <Button onClick={() => setConfirming(null)}>Cancel</Button>
                </>
              ) : <Button variant="invisible" onClick={() => setConfirming('discard')}>Discard</Button>}
            </span>
          </div>
        )}
        {draft.status === 'scheduled' && (
          <div className="box-row" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <span className="muted">Scheduled drafts can’t change. Take it off the schedule to edit it.</span>
            <Button size="small" onClick={() => void act(api.POST('/api/chapters/{code}/drafts/{id}/unschedule', path))}>Unschedule</Button>
          </div>
        )}
      </section>

      <section className="box" aria-labelledby="people-title">
        <div className="box-head" id="people-title">{open ? 'What changes for people' : 'What changed for people'} <span className="Counter">{preview.people}</span></div>
        {preview.perPerson.length === 0 && <div className="box-row muted">Nobody’s projects or access change.</div>}
        <PeopleChanges people={preview.perPerson} />
      </section>

      <section className="box" aria-labelledby="changes-title">
        <div className="box-head" id="changes-title">Changes <span className="Counter">{changes.length}</span></div>
        {changes.length === 0 && <div className="box-row muted">No changes yet. Plan them on the Projects or Automations tab with this draft open.</div>}
        <ol className="adopt-list" aria-label="Changes">
          {changes.map((c) => {
            const conflict = conflicts.find((x) => x.changeId === c.id)
            return (
              <li key={c.id}>
                <div className="task-row">
                  <div className="adopt-title">
                    <div className="adopt-name">
                      <span>{c.summary}</span>
                      {conflicted.has(c.id) && <Status tone="danger">No longer works</Status>}
                    </div>
                    <div className="adopt-meta">{conflict ? conflict.problem : `${c.createdBy ?? 'Someone'} · ${when(c.createdAt)}`}</div>
                  </div>
                  {detail.canEdit && (
                    <Button size="small" variant="invisible" onClick={() => void act(api.DELETE('/api/chapters/{code}/drafts/{id}/changes/{changeId}', { params: { path: { code, id, changeId: c.id } } }))}>
                      Remove
                    </Button>
                  )}
                </div>
              </li>
            )
          })}
        </ol>
      </section>
    </div>
  )
}

/** While a draft is open: which one, how many changes, and the way back to live data. */
export function DraftBanner({ code }: { code: string }) {
  const [active, version] = useActiveDraft(code)
  const [draft, setDraft] = useState<DraftSummary | null>(null)
  useEffect(() => {
    if (!active) return setDraft(null)
    void api.GET('/api/chapters/{code}/drafts/{id}', { params: { path: { code, id: active } } }).then(({ data }) => {
      if (data && data.draft.status === 'draft') setDraft(data.draft)
      else setActiveDraft(code, null) // released, discarded or scheduled elsewhere: back to live
    })
  }, [code, active, version])
  if (!active || !draft) return null
  return (
    <div className="draft-banner-wrap">
      <div className="draft-banner" role="status">
        <span>Planning in the draft <strong>{draft.name}</strong> · {draft.changes} {draft.changes === 1 ? 'change' : 'changes'}. Nothing changes until it’s released.</span>
        <span style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          <Button size="small" as={Link} to={`/chapters/${code}/drafts/${draft.id}`}>Review and release</Button>
          <Button size="small" variant="invisible" onClick={() => setActiveDraft(code, null)}>Back to live</Button>
        </span>
      </div>
    </div>
  )
}
