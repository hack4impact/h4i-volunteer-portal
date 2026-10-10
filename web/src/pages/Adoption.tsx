import { Banner, Button, Select, Spinner, TextInput } from '@primer/react'
import { useEffect, useState } from 'react'
import { api, type AdoptionPerson, type AdoptionReport, type AdoptionResource, type UnmatchedResource } from '../api/client'
import { Status, TOOL_NAME } from '../components/Status'

type Tone = 'ok' | 'warn' | 'off' | 'danger'

const TOOL_LABEL: Record<string, string> = { google: 'Google group', github: 'GitHub team', slack: 'Slack channel' }

const VERDICT: Record<string, [string, Tone]> = {
  expected: ['Expected', 'ok'],
  grandfathered: ['Grandfathered', 'warn'],
  unknown_account: ['Unknown account', 'danger'],
  would_add: ['Would be added', 'off'],
}

/** What the resource would be for, or why it isn't decided yet. */
function matchText(r: AdoptionResource): [string, Tone] {
  if (r.state === 'unmanaged') return ['Left unmanaged', 'off']
  const how = r.state === 'linked' ? 'Linked by hand' : r.state === 'adopted' ? 'Adopted' : 'Matched by name'
  if (r.target === 'chapter_members') return [`${how} · chapter members`, 'ok']
  if (r.target === 'chapter_leads') return [`${how} · chapter leads`, 'ok']
  if (r.target === 'project_team') return [`${how} · ${r.projectName ?? 'project'} team`, 'ok']
  return [`Needs a decision${r.suggestedSlug ? ` · no project "${r.suggestedSlug}" yet` : ''}`, 'warn']
}

type Decision = { action: string; target: string | null; projectId: string | null }

/**
 * The adoption report (build plan step 7): what already exists in the chapter's tools, who is in it today, and
 * what adopting it would mean for each person. Nothing here changes access; adoption itself happens in a draft.
 */
export function Adoption({ code }: { code: string }) {
  const [report, setReport] = useState<AdoptionReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<string | null>(null)
  const [editing, setEditing] = useState<string | null>(null)
  const [unmatched, setUnmatched] = useState<UnmatchedResource[] | null>(null)

  useEffect(() => {
    setReport(null)
    setOpen(null)
    void api.GET('/api/chapters/{code}/adoption', { params: { path: { code } } }).then(({ data }) => {
      if (!data) return setError('The adoption report could not be loaded.')
      setReport(data)
      if (data.canEdit) void api.GET('/api/adoption/unmatched').then(({ data: u }) => u && setUnmatched(u))
    })
  }, [code])

  async function decide(id: string, decision: Decision) {
    const { data, response } = await api.POST('/api/chapters/{code}/adoption/resources/{id}', { params: { path: { code, id } }, body: decision })
    if (!data) return setError(response.status === 403 ? 'Only leads and co-leads can change this, and only for their own chapter.' : `That change was refused (HTTP ${response.status}).`)
    setError(null)
    setReport(data)
    setEditing(null)
    setUnmatched((u) => u?.filter((r) => r.id !== id) ?? null)
  }

  if (!report) return error ? <div className="center"><Banner title="Not available" description={error} variant="critical" /></div> : <div className="center"><Spinner aria-label="Loading adoption report" /></div>

  const covered = report.scans.filter((s) => s.coversChapter && s.status === 'completed')
  const total = (f: (r: AdoptionResource) => number) => report.resources.reduce((n, r) => n + f(r), 0)

  return (
    <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
      {error && <Banner title="Not saved" description={error} variant="critical" />}
      <section className="box" aria-labelledby="scans">
        <div className="box-head" id="scans">Adoption scan</div>
        {report.scans.length === 0 ? (
          <div className="box-row muted">No scan has run yet. National runs it; this report fills in afterwards.</div>
        ) : (
          report.scans.map((s) => (
            <div className="box-row" key={s.tool} style={{ display: 'flex', justifyContent: 'space-between', gap: 16 }}>
              <span>{TOOL_NAME[s.tool] ?? s.tool}</span>
              <span>
                <Status tone={s.status === 'completed' ? (s.coversChapter ? 'ok' : 'warn') : s.status === 'failed' ? 'danger' : 'off'}>
                  {s.status === 'failed' ? 'Failed' : s.status === 'running' ? 'Running' : s.coversChapter ? 'Scanned' : 'Scanned, members not read for this chapter'}
                </Status>{' '}
                <span className="muted">
                  {s.status === 'failed' ? `${new Date(s.ranAt).toLocaleString()} · ${s.error ?? 'unknown error'}` : `${s.resourcesFound} found · ${new Date(s.ranAt).toLocaleString()}`}
                </span>
              </span>
            </div>
          ))
        )}
        <div className="box-row muted">
          Nothing is removed during adoption. Grandfathered people keep their access {report.grandfatheredUntil ? `until ${report.grandfatheredUntil}` : 'until national sets an end date'}.
        </div>
      </section>

      <section className="box" aria-labelledby="adoption-resources">
        <div className="box-head" id="adoption-resources">
          Channels, teams and groups
          <span className="muted" style={{ marginLeft: 'auto' }}>
            {report.resources.length} resources · {total((r) => r.expected)} expected · {total((r) => r.grandfathered)} grandfathered · {total((r) => r.unknownAccounts)} unknown · {total((r) => r.wouldAdd ?? 0)} would be added
          </span>
        </div>
        {report.resources.length === 0 && (
          <div className="box-row muted">{covered.length ? "Nothing found for this chapter's name yet. Link resources below." : 'Nothing yet.'}</div>
        )}
        {report.resources.map((r) => {
          const [text, tone] = matchText(r)
          return (
            <div className="box-row" key={r.id}>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px', alignItems: 'baseline' }}>
                <strong>{r.tool === 'slack' ? `#${r.name}` : r.name}</strong>
                <span className="muted">{TOOL_LABEL[r.tool] ?? r.tool}{r.archived ? ' · archived' : ''}{r.gone ? ' · no longer in the tool' : ''}</span>
                <Status tone={tone}>{text}</Status>
                <span style={{ marginLeft: 'auto' }} className="muted">
                  {r.snapshotAt
                    ? `${r.expected} expected · ${r.grandfathered} grandfathered · ${r.unknownAccounts} unknown · ${r.wouldAdd ?? 0} would be added`
                    : 'Members not read yet'}
                </span>
              </div>
              <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                {r.snapshotAt && (
                  <Button size="small" onClick={() => setOpen(open === r.id ? null : r.id)} aria-expanded={open === r.id}>
                    {open === r.id ? 'Hide people' : 'Show people'}
                  </Button>
                )}
                {report.canEdit && !r.gone && r.state !== 'adopted' && (
                  <Button size="small" variant="invisible" onClick={() => setEditing(editing === r.id ? null : r.id)}>Change</Button>
                )}
              </div>
              {editing === r.id && <DecisionForm report={report} resource={r} onDecide={(d) => decide(r.id, d)} onCancel={() => setEditing(null)} />}
              {open === r.id && <People code={code} resourceId={r.id} />}
            </div>
          )
        })}
      </section>

      {report.canEdit && unmatched && <Unmatched resources={unmatched} report={report} onDecide={decide} />}
    </div>
  )
}

function DecisionForm({ report, resource, onDecide, onCancel }: {
  report: AdoptionReport
  resource?: AdoptionResource
  onDecide: (d: Decision) => void
  onCancel: () => void
}) {
  const [choice, setChoice] = useState(
    resource?.target === 'project_team' ? `project:${resource.projectId}` : (resource?.target ?? 'chapter_members'),
  )
  const link = () =>
    onDecide(choice.startsWith('project:') ? { action: 'link', target: 'project_team', projectId: choice.slice(8) } : { action: 'link', target: choice, projectId: null })
  return (
    <div className="filters" style={{ border: 0, padding: '8px 0 0' }}>
      <Select aria-label="Link to" value={choice} onChange={(e) => setChoice(e.target.value)}>
        <Select.Option value="chapter_members">Chapter members</Select.Option>
        <Select.Option value="chapter_leads">Chapter leads</Select.Option>
        {report.projects.map((p) => (
          <Select.Option key={p.id} value={`project:${p.id}`}>Project team: {p.name}</Select.Option>
        ))}
      </Select>
      <Button size="small" variant="primary" onClick={link}>Link</Button>
      <Button size="small" onClick={() => onDecide({ action: 'unmanaged', target: null, projectId: null })}>Leave unmanaged</Button>
      {resource && <Button size="small" variant="invisible" onClick={() => onDecide({ action: 'reset', target: null, projectId: null })}>Back to naming convention</Button>}
      <Button size="small" variant="invisible" onClick={onCancel}>Cancel</Button>
    </div>
  )
}

function People({ code, resourceId }: { code: string; resourceId: string }) {
  const [people, setPeople] = useState<AdoptionPerson[] | null>(null)
  useEffect(() => {
    void api.GET('/api/chapters/{code}/adoption/resources/{id}', { params: { path: { code, id: resourceId } } }).then(({ data }) => setPeople(data ?? []))
  }, [code, resourceId])
  if (!people) return <Spinner size="small" aria-label="Loading people" />
  if (people.length === 0) return <div className="muted" style={{ marginTop: 8 }}>Nobody.</div>
  return (
    <table className="plain-table" aria-label="People" style={{ marginTop: 8 }}>
      <thead>
        <tr><th scope="col">Person or account</th><th scope="col">Verdict</th><th scope="col">Access</th><th scope="col">Matched by</th></tr>
      </thead>
      <tbody>
        {people.map((p, i) => {
          const [label, tone] = VERDICT[p.verdict] ?? [p.verdict, 'off']
          return (
            <tr key={i}>
              <td>{p.name ?? <span className="muted">Not in the portal</span>}{p.login && <span className="muted"> · {p.login}</span>}</td>
              <td><Status tone={tone}>{label}</Status></td>
              <td>{p.access ?? '—'}</td>
              <td>{p.matchedBy ?? '—'}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}

function Unmatched({ resources, report, onDecide }: { resources: UnmatchedResource[]; report: AdoptionReport; onDecide: (id: string, d: Decision) => void }) {
  const [query, setQuery] = useState('')
  const [editing, setEditing] = useState<string | null>(null)
  const q = query.trim().toLowerCase()
  const shown = resources.filter((r) => !q || r.name.toLowerCase().includes(q)).slice(0, 50)
  return (
    <section className="box" aria-labelledby="unmatched">
      <div className="box-head" id="unmatched">Not matched to any chapter</div>
      <div className="filters">
        <TextInput aria-label="Filter unmatched" placeholder="Filter by name" value={query} onChange={(e) => setQuery(e.target.value)} />
        <span className="count">{shown.length} of {resources.length}</span>
      </div>
      {shown.map((r) => (
        <div className="box-row" key={r.id}>
          <div style={{ display: 'flex', gap: 16, alignItems: 'baseline' }}>
            <strong>{r.tool === 'slack' ? `#${r.name}` : r.name}</strong>
            <span className="muted">{TOOL_LABEL[r.tool] ?? r.tool}{r.archived ? ' · archived' : ''}</span>
            <Button size="small" variant="invisible" style={{ marginLeft: 'auto' }} onClick={() => setEditing(editing === r.id ? null : r.id)}>
              Link to this chapter
            </Button>
          </div>
          {editing === r.id && <DecisionForm report={report} onDecide={(d) => onDecide(r.id, d)} onCancel={() => setEditing(null)} />}
        </div>
      ))}
    </section>
  )
}
