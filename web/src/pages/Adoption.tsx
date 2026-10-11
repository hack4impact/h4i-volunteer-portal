import { ActionList, ActionMenu, Banner, Button, Checkbox, IconButton, Label, Select, Spinner, TextInput } from '@primer/react'
import { ChevronDownIcon, HashIcon, KebabHorizontalIcon, MailIcon, PeopleIcon, SearchIcon, type Icon } from '@primer/octicons-react'
import { useEffect, useMemo, useState } from 'react'
import { api, type AdoptionPerson, type AdoptionReport, type AdoptionResource, type UnmatchedResource } from '../api/client'
import { Status, TOOL_NAME } from '../components/Status'

const PAGE = 25

const TOOL: Record<string, { icon: Icon; noun: string }> = {
  slack: { icon: HashIcon, noun: 'Slack channel' },
  google: { icon: MailIcon, noun: 'Google group' },
  github: { icon: PeopleIcon, noun: 'GitHub team' },
}

const VERDICT: Record<string, [string, 'ok' | 'warn' | 'off' | 'danger']> = {
  expected: ['Expected', 'ok'],
  grandfathered: ['Grandfathered', 'warn'],
  unknown_account: ['Unknown account', 'danger'],
  would_add: ['Would be added', 'off'],
}

type Decision = { action: string; target: string | null; projectId: string | null }
type Bucket = 'all' | 'decide' | 'ready' | 'unmanaged'

/** Which tab a resource belongs to: it needs a decision, it has a target, or it's left alone. */
function bucket(r: AdoptionResource): Exclude<Bucket, 'all'> {
  if (r.state === 'unmanaged') return 'unmanaged'
  return r.target ? 'ready' : 'decide'
}

function targetText(r: AdoptionResource) {
  if (r.target === 'chapter_members') return 'chapter members'
  if (r.target === 'chapter_leads') return 'chapter leads'
  if (r.target === 'project_team') return `${r.projectName ?? 'Project'} team`
  return null
}

/**
 * The adoption report (build plan step 7), laid out like a GitHub list: a sidebar with the scan, a toolbar with
 * search, state tabs and filters, and rows whose counts sit in fixed columns. Nothing here changes access.
 */
export function Adoption({ code }: { code: string }) {
  const [report, setReport] = useState<AdoptionReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [unmatched, setUnmatched] = useState<UnmatchedResource[] | null>(null)

  useEffect(() => {
    setReport(null)
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
    setUnmatched((u) => u?.filter((r) => r.id !== id) ?? null)
  }

  if (!report) return error ? <div className="center"><Banner title="Not available" description={error} variant="critical" /></div> : <div className="center"><Spinner aria-label="Loading adoption report" /></div>

  return (
    <div className="page-body">
      <main style={{ display: 'grid', gap: 24, alignContent: 'start', minWidth: 0 }}>
        {error && <Banner title="Not saved" description={error} variant="critical" onDismiss={() => setError(null)} />}
        <ResourceList code={code} report={report} onDecide={decide} />
        {report.canEdit && unmatched && <Unmatched resources={unmatched} report={report} onDecide={decide} />}
      </main>
      <ScanPane report={report} />
    </div>
  )
}

function ScanPane({ report }: { report: AdoptionReport }) {
  const last = report.scans.map((s) => s.ranAt).sort().at(-1)
  return (
    <aside>
      <div className="pane-section" style={{ paddingTop: 0 }}>
        <div className="pane-label">Adoption scan</div>
        {report.scans.length === 0 ? (
          <div className="muted">No scan has run yet. National runs it; this report fills in afterwards.</div>
        ) : (
          <>
            <ul className="pane-list">
              {report.scans.map((s) => {
                const ToolIcon = TOOL[s.tool]?.icon ?? HashIcon
                return (
                  <li key={s.tool}>
                    <ToolIcon size={16} className="muted-icon" />
                    <span>{TOOL_NAME[s.tool] ?? s.tool}</span>
                    {s.status === 'completed' ? (
                      <span className="num">{s.resourcesFound.toLocaleString()} found</span>
                    ) : (
                      <Status tone={s.status === 'failed' ? 'danger' : 'off'}>{s.status === 'failed' ? 'Failed' : 'Running'}</Status>
                    )}
                    {s.error && <span className="muted pane-wide">{s.error}</span>}
                    {s.status === 'completed' && !s.coversChapter && <span className="muted pane-wide">Members not read for this chapter</span>}
                  </li>
                )
              })}
            </ul>
            {last && <div className="muted">Last scanned {new Date(last).toLocaleString()}</div>}
          </>
        )}
      </div>
      <div className="pane-section">
        <div className="pane-label">Grandfathered access</div>
        <div>{report.grandfatheredUntil ? <>Kept until <strong>{report.grandfatheredUntil}</strong></> : 'Kept until national sets an end date'}</div>
        <div className="muted">Nothing is removed during adoption.</div>
      </div>
      <div className="pane-section muted">
        Private Slack channels show up only after someone in the channel invites the portal's Slack app; Slack doesn't let apps see the rest.
      </div>
    </aside>
  )
}

function ResourceList({ code, report, onDecide }: { code: string; report: AdoptionReport; onDecide: (id: string, d: Decision) => void }) {
  const [tab, setTab] = useState<Bucket>('all')
  const [query, setQuery] = useState('')
  const [tool, setTool] = useState('')
  const [archived, setArchived] = useState(false)
  const [sort, setSort] = useState<'name' | 'people'>('name')
  const [shown, setShown] = useState(PAGE)
  const [open, setOpen] = useState<string | null>(null)

  const base = useMemo(() => {
    const q = query.trim().toLowerCase()
    return report.resources.filter((r) => (archived || !r.archived) && (!tool || r.tool === tool) && (!q || r.name.toLowerCase().includes(q)))
  }, [report, query, tool, archived])
  const counts: Record<Bucket, number> = { all: base.length, decide: 0, ready: 0, unmanaged: 0 }
  base.forEach((r) => counts[bucket(r)]++)
  const people = (r: AdoptionResource) => r.expected + r.grandfathered + r.unknownAccounts
  const rows = base
    .filter((r) => tab === 'all' || bucket(r) === tab)
    .sort((a, b) => (sort === 'people' ? people(b) - people(a) : 0) || a.name.localeCompare(b.name))
  const archivedCount = report.resources.filter((r) => r.archived).length
  const tools = [...new Set(report.resources.map((r) => r.tool))]
  const total = (f: (r: AdoptionResource) => number) => rows.reduce((n, r) => n + f(r), 0)
  const tabs: [Bucket, string][] = [['all', 'All'], ['decide', 'Needs a decision'], ['ready', 'Ready'], ['unmanaged', 'Unmanaged']]
  const reset = () => setShown(PAGE)

  return (
    <section className="box" aria-labelledby="adoption-resources">
      <div className="box-head" id="adoption-resources">Channels, teams and groups</div>
      <div className="list-toolbar">
        <TextInput leadingVisual={SearchIcon} aria-label="Filter by name" placeholder="Filter by name" value={query}
          onChange={(e) => { setQuery(e.target.value); reset() }} className="toolbar-search" />
        <Select aria-label="Tool" value={tool} onChange={(e) => { setTool(e.target.value); reset() }}>
          <Select.Option value="">All tools</Select.Option>
          {tools.map((t) => <Select.Option key={t} value={t}>{TOOL_NAME[t] ?? t}</Select.Option>)}
        </Select>
        {archivedCount > 0 && (
          <label className="check"><Checkbox checked={archived} onChange={(e) => { setArchived(e.target.checked); reset() }} /> Show archived ({archivedCount})</label>
        )}
      </div>
      <div className="state-tabs" role="group" aria-label="Filter by state">
        {tabs.map(([key, label]) => (
          <button key={key} type="button" aria-pressed={tab === key} className={tab === key ? 'selected' : ''} onClick={() => { setTab(key); reset() }}>
            {label} <span className="Counter">{counts[key]}</span>
          </button>
        ))}
      </div>
      <div className="adopt-row adopt-head">
        <span />
        <SortHeader label="Name" active={sort === 'name'} onClick={() => setSort('name')} />
        <span className="num" title="Should have it and does">Expected</span>
        <SortHeader label="Grandfathered" numeric active={sort === 'people'} onClick={() => setSort('people')} title="Has it, but the portal wouldn't give it; keeps it for now. Click to sort by most people" />
        <span className="num" title="Accounts not linked to anyone in the portal">Unknown</span>
        <span className="num" title="Should have it but doesn't">Would add</span>
        <span />
      </div>
      {rows.length === 0 && <div className="box-row muted">Nothing here.</div>}
      <ul className="adopt-list" aria-label="Resources">
        {rows.slice(0, shown).map((r) => (
          <ResourceRow key={r.id} code={code} r={r} report={report} open={open === r.id}
            onToggle={() => setOpen(open === r.id ? null : r.id)} onDecide={(d) => onDecide(r.id, d)} />
        ))}
      </ul>
      {rows.length > 0 && (
        <div className="adopt-row adopt-foot">
          <span />
          <span>{rows.length > shown ? `Showing ${shown} of ${rows.length}` : `${rows.length} ${rows.length === 1 ? 'resource' : 'resources'}`} · totals</span>
          <span className="num">{total((r) => r.expected)}</span>
          <span className="num">{total((r) => r.grandfathered)}</span>
          <span className="num">{total((r) => r.unknownAccounts)}</span>
          <span className="num">{total((r) => r.wouldAdd ?? 0)}</span>
          <span />
        </div>
      )}
      {rows.length > shown && (
        <div className="show-more"><Button size="small" onClick={() => setShown(shown + PAGE)}>Show {Math.min(PAGE, rows.length - shown)} more</Button></div>
      )}
    </section>
  )
}

function ResourceRow({ code, r, report, open, onToggle, onDecide }: {
  code: string; r: AdoptionResource; report: AdoptionReport; open: boolean; onToggle: () => void; onDecide: (d: Decision) => void
}) {
  const ToolIcon = TOOL[r.tool]?.icon ?? HashIcon
  const target = targetText(r)
  // Matched by name is the usual case, so only the exceptions are spelled out.
  const how = r.state === 'linked' ? 'linked by hand' : r.state === 'adopted' ? 'adopted' : null
  const read = r.snapshotAt != null
  // Zeros stay blank so the numbers that matter stand out.
  const n = (value: number) => <span className="num">{value === 0 ? '' : value}</span>
  return (
    <li className={open ? 'open' : undefined}>
      <div className="adopt-row">
        <ToolIcon size={16} className="muted-icon" aria-label={TOOL[r.tool]?.noun ?? r.tool} />
        <div className="adopt-title">
          <div className="adopt-name">
            {read ? (
              <button type="button" className="name-button" aria-expanded={open} onClick={onToggle}>{r.name}</button>
            ) : <span className="name-text">{r.name}</span>}
            {r.archived && <Label variant="secondary">Archived</Label>}
            {r.gone && <Label variant="danger">Gone from {TOOL_NAME[r.tool] ?? r.tool}</Label>}
          </div>
          {(target || r.state === 'unmanaged' || how) && (
            <div className="adopt-meta">
              {r.state === 'unmanaged' ? 'Left unmanaged' : target && `For ${target}`}
              {how && ` · ${how}`}
            </div>
          )}
          <div className="adopt-meta narrow-only">
            {read ? `${r.grandfathered} grandfathered · ${r.unknownAccounts} unknown · ${r.wouldAdd ?? 0} would add` : 'Members not read'}
          </div>
        </div>
        {read ? <>{n(r.expected)}{n(r.grandfathered)}{n(r.unknownAccounts)}{n(r.wouldAdd ?? 0)}</> : <span className="not-read" title="Archived, or not read by the last scan">Not read</span>}
        <span className="row-menu">{report.canEdit && !r.gone && r.state !== 'adopted' && <DecisionMenu name={r.name} report={report} current={r} onDecide={onDecide} />}</span>
      </div>
      {open && <People code={code} resourceId={r.id} />}
    </li>
  )
}

function SortHeader({ label, active, numeric, onClick, title }: { label: string; active: boolean; numeric?: boolean; onClick: () => void; title?: string }) {
  return (
    <button type="button" className={`sort-header${numeric ? ' num' : ''}${active ? ' active' : ''}`} aria-pressed={active} onClick={onClick} title={title}>
      {label}{active && <ChevronDownIcon size={12} />}
    </button>
  )
}

function DecisionMenu({ name, report, current, onDecide, label }: {
  name: string; report: AdoptionReport; current?: AdoptionResource; onDecide: (d: Decision) => void; label?: string
}) {
  const link = (target: string, projectId: string | null = null) => onDecide({ action: 'link', target, projectId })
  const is = (target: string, projectId?: string) => current?.target === target && (!projectId || current.projectId === projectId)
  return (
    <ActionMenu>
      <ActionMenu.Anchor>
        {label ? <Button size="small" trailingVisual={ChevronDownIcon}>{label}</Button>
          : <IconButton icon={KebabHorizontalIcon} variant="invisible" size="small" aria-label={`Change ${name}`} />}
      </ActionMenu.Anchor>
      <ActionMenu.Overlay width="medium" align="end">
        <ActionList>
          <ActionList.Group selectionVariant="single">
            <ActionList.GroupHeading>Link to</ActionList.GroupHeading>
            <ActionList.Item selected={is('chapter_members')} onSelect={() => link('chapter_members')}>Chapter members</ActionList.Item>
            <ActionList.Item selected={is('chapter_leads')} onSelect={() => link('chapter_leads')}>Chapter leads</ActionList.Item>
            {report.projects.map((p) => (
              <ActionList.Item key={p.id} selected={is('project_team', p.id)} onSelect={() => link('project_team', p.id)}>{p.name} team</ActionList.Item>
            ))}
          </ActionList.Group>
          <ActionList.Divider />
          <ActionList.Item onSelect={() => onDecide({ action: 'unmanaged', target: null, projectId: null })}>Leave unmanaged</ActionList.Item>
          {current && <ActionList.Item onSelect={() => onDecide({ action: 'reset', target: null, projectId: null })}>Back to naming convention</ActionList.Item>}
        </ActionList>
      </ActionMenu.Overlay>
    </ActionMenu>
  )
}

function People({ code, resourceId }: { code: string; resourceId: string }) {
  const [people, setPeople] = useState<AdoptionPerson[] | null>(null)
  const [verdict, setVerdict] = useState('')
  useEffect(() => {
    void api.GET('/api/chapters/{code}/adoption/resources/{id}', { params: { path: { code, id: resourceId } } }).then(({ data }) => setPeople(data ?? []))
  }, [code, resourceId])
  if (!people) return <div className="people-panel"><Spinner size="small" aria-label="Loading people" /></div>
  const counts = Object.fromEntries(Object.keys(VERDICT).map((v) => [v, people.filter((p) => p.verdict === v).length]))
  const rows = people.filter((p) => !verdict || p.verdict === verdict)
  return (
    <div className="people-panel">
      <div className="state-tabs compact" role="group" aria-label="Filter people">
        <button type="button" aria-pressed={!verdict} className={!verdict ? 'selected' : ''} onClick={() => setVerdict('')}>All <span className="Counter">{people.length}</span></button>
        {Object.entries(VERDICT).filter(([v]) => counts[v]! > 0).map(([v, [text]]) => (
          <button type="button" key={v} aria-pressed={verdict === v} className={verdict === v ? 'selected' : ''} onClick={() => setVerdict(v)}>
            {text} <span className="Counter">{counts[v]}</span>
          </button>
        ))}
      </div>
      {people.length === 0 ? <div className="muted">Nobody.</div> : (
        <div className="table-scroll">
          <table className="people-table" aria-label="People">
            <colgroup><col /><col style={{ width: 170 }} /><col style={{ width: 80 }} /><col style={{ width: 100 }} /></colgroup>
            <thead><tr><th scope="col">Person or account</th><th scope="col">Verdict</th><th scope="col">Access</th><th scope="col">Matched by</th></tr></thead>
            <tbody>
              {rows.map((p, i) => {
                const [text, tone] = VERDICT[p.verdict] ?? [p.verdict, 'off']
                return (
                  <tr key={i}>
                    <td>{p.name ?? <span className="muted">Not in the portal</span>}{p.login && <span className="muted"> · {p.login}</span>}</td>
                    <td><Status tone={tone}>{text}</Status></td>
                    <td>{p.access ?? <span className="muted">—</span>}</td>
                    <td>{p.matchedBy ?? <span className="muted">—</span>}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

function Unmatched({ resources, report, onDecide }: { resources: UnmatchedResource[]; report: AdoptionReport; onDecide: (id: string, d: Decision) => void }) {
  const [query, setQuery] = useState('')
  const [tool, setTool] = useState('')
  const [shown, setShown] = useState(PAGE)
  const [expanded, setExpanded] = useState(false)
  const q = query.trim().toLowerCase()
  const rows = resources.filter((r) => (!tool || r.tool === tool) && (!q || r.name.toLowerCase().includes(q)))
  const tools = [...new Set(resources.map((r) => r.tool))]
  if (!expanded) {
    return (
      <section className="box" aria-labelledby="unmatched">
        <div className="box-head" id="unmatched">
          Not matched to any chapter <span className="Counter">{resources.length}</span>
          <Button size="small" variant="invisible" style={{ marginLeft: 'auto' }} onClick={() => setExpanded(true)}>Show</Button>
        </div>
        <div className="box-row muted">Channels, teams and groups whose names don't start with a chapter's code. Link one to this chapter if it's yours.</div>
      </section>
    )
  }
  return (
    <section className="box" aria-labelledby="unmatched">
      <div className="box-head" id="unmatched">
        Not matched to any chapter <span className="Counter">{resources.length}</span>
        <Button size="small" variant="invisible" style={{ marginLeft: 'auto' }} onClick={() => setExpanded(false)}>Hide</Button>
      </div>
      <div className="list-toolbar">
        <TextInput leadingVisual={SearchIcon} aria-label="Filter unmatched" placeholder="Filter by name" value={query}
          onChange={(e) => { setQuery(e.target.value); setShown(PAGE) }} className="toolbar-search" />
        <Select aria-label="Unmatched tool" value={tool} onChange={(e) => { setTool(e.target.value); setShown(PAGE) }}>
          <Select.Option value="">All tools</Select.Option>
          {tools.map((t) => <Select.Option key={t} value={t}>{TOOL_NAME[t] ?? t}</Select.Option>)}
        </Select>
        <span className="muted toolbar-count">{rows.length === resources.length ? `${rows.length}` : `${rows.length} of ${resources.length}`}</span>
      </div>
      <ul className="adopt-list" aria-label="Unmatched resources">
        {rows.slice(0, shown).map((r) => {
          const ToolIcon = TOOL[r.tool]?.icon ?? HashIcon
          return (
            <li key={r.id}>
              <div className="unmatched-row">
                <ToolIcon size={16} className="muted-icon" aria-label={TOOL[r.tool]?.noun ?? r.tool} />
                <div className="adopt-name"><span className="name-text">{r.name}</span>{r.archived && <Label variant="secondary">Archived</Label>}</div>
                <DecisionMenu name={r.name} report={report} onDecide={(d) => onDecide(r.id, d)} label="Link to this chapter" />
              </div>
            </li>
          )
        })}
      </ul>
      {rows.length === 0 && <div className="box-row muted">Nothing matches.</div>}
      {rows.length > shown && (
        <div className="show-more"><Button size="small" onClick={() => setShown(shown + PAGE)}>Show {Math.min(PAGE, rows.length - shown)} more</Button></div>
      )}
    </section>
  )
}
