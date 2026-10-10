import { Banner, Button, Checkbox, Select, Spinner, TextInput } from '@primer/react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import {
  api, reason, type ChapterResourceOption, type MemberRow, type ProjectDetail, type ProjectResourceRow, type ProjectRoleOption, type TermOption,
} from '../api/client'
import { CHANGE_LABEL, PROJECT_STATUS, STATUS_LABEL, Status, TOOL_NAME, resourceName } from '../components/Status'

const TOOL_ORDER = ['slack', 'google', 'github', 'vaultwarden']
const NEXT_STATUS: Record<string, [string, string][]> = {
  draft: [['active', 'Activate']],
  active: [['paused', 'Pause']],
  paused: [['active', 'Resume']],
  closed: [],
}

function audienceText(r: ProjectResourceRow) {
  return r.audience === 'role' ? `${r.role ?? 'One role'} only` : r.audience === 'leads' ? 'Project leads' : 'Whole team'
}

/**
 * One project (build plan step 8): its team, its resources with who gets each and why, where its Notion page goes,
 * and what the latest dry run would change. Every tool is still in dry run, so nothing here changes access yet.
 */
export function ProjectPage({ code, slug, members }: { code: string; slug: string; members: MemberRow[] | null }) {
  const [project, setProject] = useState<ProjectDetail | null>(null)
  const [missing, setMissing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [roles, setRoles] = useState<ProjectRoleOption[]>([])
  const [running, setRunning] = useState(false)
  const poll = useRef<number | null>(null)
  const path = { params: { path: { code, slug } } }

  useEffect(() => {
    setProject(null)
    void api.GET('/api/chapters/{code}/projects/{slug}', { params: { path: { code, slug } } }).then(({ data }) => (data ? setProject(data) : setMissing(true)))
    void api.GET('/api/project-roles').then(({ data }) => setRoles(data ?? []))
    return () => { if (poll.current) window.clearInterval(poll.current) }
  }, [code, slug])

  /** Runs a change; the server answers with the updated project. */
  async function apply(call: Promise<{ data?: ProjectDetail; error?: unknown; response: Response }>) {
    const { data, error: failed, response } = await call
    if (!data) { setError(reason(failed, response)); return false }
    setError(null)
    setProject(data)
    return true
  }

  async function runDryRun() {
    const before = project?.lastDryRunAt
    const { response } = await api.POST('/api/chapters/{code}/sync/run', { params: { path: { code } } })
    if (response.status !== 202) return setError(reason(undefined, response, 'The dry run could not be started'))
    setRunning(true)
    let tries = 0
    poll.current = window.setInterval(async () => {
      tries++
      const { data } = await api.GET('/api/chapters/{code}/projects/{slug}', path)
      if (data && (data.lastDryRunAt !== before || tries > 20)) {
        setProject(data)
        setRunning(false)
        if (poll.current) window.clearInterval(poll.current)
      }
    }, 1500)
  }

  if (missing) return <div className="center"><Banner title="Not found" description="There's no such project in this chapter." variant="critical" /></div>
  if (!project) return <div className="center"><Spinner aria-label="Loading project" /></div>

  const [statusLabel, statusTone] = PROJECT_STATUS[project.status] ?? [project.status, 'off']
  const edit = project.canEdit && project.status !== 'closed'
  const gated = project.resources.some((r) => r.requiresAgreement)
  const tools = [...TOOL_ORDER, ...project.resources.map((r) => r.tool).filter((t) => !TOOL_ORDER.includes(t))]
    .filter((t, i, all) => all.indexOf(t) === i && project.resources.some((r) => r.tool === t))

  return (
    <div className="page-body">
      <div style={{ gridColumn: '1 / -1', display: 'flex', flexWrap: 'wrap', gap: '8px 16px', alignItems: 'center' }}>
        <div>
          <div className="eyebrow"><Link to={`/chapters/${code}/projects`}>Projects</Link></div>
          <h2 className="project-title">{project.name}</h2>
        </div>
        <Status tone={statusTone}>{statusLabel}</Status>
        <span style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          {edit && NEXT_STATUS[project.status]?.map(([to, label]) => (
            <Button key={to} variant={to === 'active' ? 'primary' : 'default'} onClick={() => apply(api.POST('/api/chapters/{code}/projects/{slug}/status', { ...path, body: { status: to } }))}>
              {label}
            </Button>
          ))}
          {edit && (
            <Button variant="danger" onClick={() => window.confirm(`Close ${project.name}? Its members lose the access it gave them, and it can't be reopened.`) &&
              apply(api.POST('/api/chapters/{code}/projects/{slug}/status', { ...path, body: { status: 'closed' } }))}>
              Close
            </Button>
          )}
        </span>
        {error && <div style={{ flexBasis: '100%' }}><Banner title="Not saved" description={error} variant="critical" onDismiss={() => setError(null)} /></div>}
        {project.status === 'draft' && (
          <div style={{ flexBasis: '100%' }}>
            <Banner title="Draft" description="Nobody gets access from a draft. The numbers below show what activating it would do." variant="info" />
          </div>
        )}
      </div>

      <main style={{ display: 'grid', gap: 24, alignContent: 'start' }}>
        <Team project={project} members={members} roles={roles} edit={edit} gated={gated} apply={apply} code={code} slug={slug} />

        {tools.map((tool) => (
          <section className="box" key={tool} aria-labelledby={`tool-${tool}`}>
            <div className="box-head" id={`tool-${tool}`}>{TOOL_NAME[tool] ?? tool}</div>
            {project.resources.filter((r) => r.tool === tool).map((r) => (
              <ResourceRow key={r.resourceId} r={r} roles={roles} edit={edit} draft={project.preview.asIfActive}
                onSave={(body) => apply(api.PUT('/api/chapters/{code}/projects/{slug}/resources/{resourceId}', { params: { path: { code, slug, resourceId: r.resourceId } }, body }))}
                onDetach={() => window.confirm(`Detach ${resourceName(r.tool, r.name)} from ${project.name}?`) &&
                  apply(api.DELETE('/api/chapters/{code}/projects/{slug}/resources/{resourceId}', { params: { path: { code, slug, resourceId: r.resourceId } } }))}
              />
            ))}
          </section>
        ))}
        {edit && <AddResource code={code} project={project} roles={roles} onAdd={(body) => apply(api.POST('/api/chapters/{code}/projects/{slug}/resources', { ...path, body }))} />}

        <section className="box" aria-labelledby="planned">
          <div className="box-head" id="planned">
            Planned changes (dry run)
            {project.canEdit && (
              <Button size="small" style={{ marginLeft: 'auto' }} onClick={runDryRun} disabled={running}>
                {running ? 'Running…' : 'Run dry run'}
              </Button>
            )}
          </div>
          <div className="box-row muted">
            {project.lastDryRunAt ? `Last dry run ${new Date(project.lastDryRunAt).toLocaleString()}.` : 'No dry run yet.'}
            {project.lastDryRunAt && project.plannedOutOfDate ? ' The project changed since then: run it again to see the effect.' : ''}
            {' '}Nothing is applied: every tool is in dry run.
          </div>
          {project.planned.length === 0 && project.lastDryRunAt && <div className="box-row muted">Nothing to change for this project.</div>}
          {project.planned.slice(0, 50).map((c, i) => (
            <div className="box-row" key={i}>
              <strong>{CHANGE_LABEL[c.kind] ?? c.kind}</strong>{' '}
              {c.person && <span>{c.person} · </span>}
              <span>{resourceName(c.tool, c.resource)}</span>{' '}
              <span className="muted">{c.fromAccess && c.toAccess ? `${c.fromAccess} → ${c.toAccess}` : (c.toAccess ?? '')}</span>
            </div>
          ))}
          {project.planned.length > 50 && <div className="box-row muted">And {project.planned.length - 50} more.</div>}
        </section>
      </main>

      <aside>
        <Details project={project} code={code} edit={edit} apply={(body) => apply(api.PUT('/api/chapters/{code}/projects/{slug}', { ...path, body }))} />
        <div className="pane-section">
          <div className="pane-label">Who gets access</div>
          <div>
            {project.preview.people} people · {project.preview.grants} grants
            {project.preview.awaitingAgreement > 0 && <> · {project.preview.awaitingAgreement} after agreement</>}
          </div>
          {gated && <div className="muted">{project.agreementsSigned} of {project.members.length} signed the project agreement</div>}
        </div>
        <div className="pane-section">
          <div className="pane-label">Notion page</div>
          <NotionText project={project} code={code} />
        </div>
        <div className="pane-section">
          <div className="pane-label">Activity</div>
          {project.activity.length === 0 && <div className="muted">Nothing yet.</div>}
          {project.activity.map((a, i) => (
            <div key={i} style={{ marginBottom: 6 }}>
              <div>{a.detail ?? a.action}</div>
              <div className="muted">{a.actor ?? 'System'} · {new Date(a.at).toLocaleString()}</div>
            </div>
          ))}
        </div>
      </aside>
    </div>
  )
}

type Apply = (call: Promise<{ data?: ProjectDetail; error?: unknown; response: Response }>) => Promise<boolean>

function Team({ project, members, roles, edit, gated, apply, code, slug }: {
  project: ProjectDetail; members: MemberRow[] | null; roles: ProjectRoleOption[]; edit: boolean; gated: boolean; apply: Apply; code: string; slug: string
}) {
  const [personId, setPersonId] = useState('')
  const [roleId, setRoleId] = useState('')
  const onTeam = new Set(project.members.map((m) => m.personId))
  const candidates = (members ?? []).filter((m) => !onTeam.has(m.personId) && ['active', 'alumni', 'removal_requested'].includes(m.status))
  const member = (personId: string) => ({ params: { path: { code, slug, personId } } })
  return (
    <section className="box" aria-labelledby="team">
      <div className="box-head" id="team">
        Team <span className="muted">{project.members.length}</span>
        {gated && <span className="muted" style={{ marginLeft: 'auto' }}>{project.agreementsSigned} of {project.members.length} agreements signed</span>}
      </div>
      {project.members.length === 0 && <div className="box-row muted">No members yet.</div>}
      {project.members.map((m) => (
        <div className="box-row" key={m.personId} style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px', alignItems: 'center' }}>
          <strong>{m.name}</strong>
          {m.status !== 'active' && <span className="muted">{STATUS_LABEL[m.status] ?? m.status}</span>}
          {gated && (
            <Status tone={m.agreementStatus === 'pending' ? 'warn' : 'ok'}>
              {m.agreementStatus === 'pending' ? 'Agreement not signed' : m.agreementStatus === 'waived' ? 'Agreement waived' : 'Agreement signed'}
            </Status>
          )}
          <span style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
            {edit ? (
              <Select aria-label={`${m.name}'s role`} size="small" value={m.roleId ?? ''}
                onChange={(e) => apply(api.PUT('/api/chapters/{code}/projects/{slug}/members/{personId}', { ...member(m.personId), body: { roleId: e.target.value || null } }))}>
                <Select.Option value="">No role</Select.Option>
                {roles.map((r) => <Select.Option key={r.id} value={r.id}>{r.name}</Select.Option>)}
              </Select>
            ) : <span className="muted">{m.role ?? 'No role'}</span>}
            {edit && <Button size="small" variant="invisible" onClick={() => apply(api.DELETE('/api/chapters/{code}/projects/{slug}/members/{personId}', member(m.personId)))}>Remove</Button>}
          </span>
        </div>
      ))}
      {edit && (
        <div className="box-row inline-form">
          <Select aria-label="Person to add" value={personId} onChange={(e) => setPersonId(e.target.value)}>
            <Select.Option value="">Add a member…</Select.Option>
            {candidates.map((m) => <Select.Option key={m.personId} value={m.personId}>{m.name}{m.status === 'alumni' ? ' (alumni)' : ''}</Select.Option>)}
          </Select>
          <Select aria-label="Role" value={roleId} onChange={(e) => setRoleId(e.target.value)}>
            <Select.Option value="">No role</Select.Option>
            {roles.map((r) => <Select.Option key={r.id} value={r.id}>{r.name}</Select.Option>)}
          </Select>
          <Button disabled={!personId} onClick={async () => {
            if (await apply(api.POST('/api/chapters/{code}/projects/{slug}/members', { params: { path: { code, slug } }, body: { personId, roleId: roleId || null } }))) setPersonId('')
          }}>Add</Button>
        </div>
      )}
    </section>
  )
}

type ResourceBody = { resourceId: string | null; tool: string | null; name: string | null; audience: string; roleId: string | null; access: string; requiresAgreement: boolean; tags: string[] }

function AccessFields({ value, onChange, roles }: { value: ResourceBody; onChange: (v: ResourceBody) => void; roles: ProjectRoleOption[] }) {
  return (
    <>
      <Select aria-label="Who gets it" value={value.audience === 'role' ? `role:${value.roleId}` : value.audience}
        onChange={(e) => {
          const v = e.target.value
          onChange(v.startsWith('role:') ? { ...value, audience: 'role', roleId: v.slice(5) } : { ...value, audience: v, roleId: null })
        }}>
        <Select.Option value="team">Whole team</Select.Option>
        <Select.Option value="leads">Project leads</Select.Option>
        {roles.map((r) => <Select.Option key={r.id} value={`role:${r.id}`}>{r.name} only</Select.Option>)}
      </Select>
      <Select aria-label="Access" value={value.access} onChange={(e) => onChange({ ...value, access: e.target.value })}>
        <Select.Option value="read">Read</Select.Option>
        <Select.Option value="write">Write</Select.Option>
        <Select.Option value="admin">Admin</Select.Option>
      </Select>
      <label className="check"><Checkbox checked={value.requiresAgreement} onChange={(e) => onChange({ ...value, requiresAgreement: e.target.checked })} /> After agreement</label>
      <TextInput aria-label="Tags" placeholder="tags, comma-separated" value={value.tags.join(', ')}
        onChange={(e) => onChange({ ...value, tags: e.target.value.split(',').map((t) => t.trim()) })} />
    </>
  )
}

function ResourceRow({ r, roles, edit, draft, onSave, onDetach }: {
  r: ProjectResourceRow; roles: ProjectRoleOption[]; edit: boolean; draft: boolean; onSave: (b: ResourceBody) => Promise<boolean>; onDetach: () => void
}) {
  const [editing, setEditing] = useState<ResourceBody | null>(null)
  const state: Record<string, [string, 'ok' | 'warn' | 'off']> = { to_create: ['To be created', 'warn'], exists: ['In the tool', 'ok'], archived: ['Archived', 'off'] }
  const [stateText, stateTone] = state[r.state] ?? [r.state, 'off']
  return (
    <div className="box-row">
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px', alignItems: 'baseline' }}>
        <strong>{resourceName(r.tool, r.name)}</strong>
        <Status tone={stateTone}>{stateText}</Status>
        <span>{audienceText(r)} · {r.access}{r.requiresAgreement ? ' · after agreement' : ''}</span>
        {r.tags.length > 0 && <span className="muted">{r.tags.map((t) => `#${t}`).join(' ')}</span>}
        <span className="muted" style={{ marginLeft: 'auto' }}>
          {r.getting} {draft ? 'would get it' : 'get it'}{r.awaitingAgreement > 0 ? ` · ${r.awaitingAgreement} after agreement` : ''}
        </span>
      </div>
      {r.sharedWith.length > 0 && <div className="muted">Shared with {r.sharedWith.join(', ')}</div>}
      {r.existsInTool && (
        <div className="muted">
          {TOOL_NAME[r.tool] ?? r.tool} already has something called {r.name} (found by the adoption scan). If it's this project's, link it on the Adoption tab instead of creating a new one.
        </div>
      )}
      {edit && !editing && (
        <div className="inline-form">
          <Button size="small" variant="invisible" onClick={() => setEditing({ resourceId: null, tool: null, name: null, audience: r.audience, roleId: r.roleId, access: r.access, requiresAgreement: r.requiresAgreement, tags: r.tags })}>Change</Button>
          <Button size="small" variant="invisible" onClick={onDetach}>Detach</Button>
        </div>
      )}
      {editing && (
        <div className="inline-form">
          <AccessFields value={editing} onChange={setEditing} roles={roles} />
          <Button size="small" variant="primary" onClick={async () => { if (await onSave({ ...editing, tags: editing.tags.filter(Boolean) })) setEditing(null) }}>Save</Button>
          <Button size="small" onClick={() => setEditing(null)}>Cancel</Button>
        </div>
      )}
    </div>
  )
}

function AddResource({ code, project, roles, onAdd }: { code: string; project: ProjectDetail; roles: ProjectRoleOption[]; onAdd: (b: ResourceBody) => Promise<boolean> }) {
  const empty: ResourceBody = { resourceId: null, tool: 'slack', name: '', audience: 'team', roleId: null, access: 'write', requiresAgreement: false, tags: [] }
  const [open, setOpen] = useState(false)
  const [mode, setMode] = useState<'new' | 'existing'>('new')
  const [body, setBody] = useState<ResourceBody>(empty)
  const [existing, setExisting] = useState<ChapterResourceOption[]>([])
  useEffect(() => {
    if (open) void api.GET('/api/chapters/{code}/resources', { params: { path: { code } } }).then(({ data }) => setExisting(data ?? []))
  }, [open, code])
  const attached = new Set(project.resources.map((r) => r.resourceId))
  if (!open) return <div><Button onClick={() => setOpen(true)}>Add a resource</Button></div>
  return (
    <section className="box" aria-labelledby="add-resource">
      <div className="box-head" id="add-resource">Add a resource</div>
      <div className="box-row inline-form">
        <Select aria-label="New or existing" value={mode} onChange={(e) => { setMode(e.target.value as 'new' | 'existing'); setBody(empty) }}>
          <Select.Option value="new">A new one</Select.Option>
          <Select.Option value="existing">One this chapter already has</Select.Option>
        </Select>
        {mode === 'new' ? (
          <>
            <Select aria-label="Tool" value={body.tool ?? 'slack'} onChange={(e) => setBody({ ...body, tool: e.target.value })}>
              {TOOL_ORDER.map((t) => <Select.Option key={t} value={t}>{TOOL_NAME[t]}</Select.Option>)}
            </Select>
            <TextInput aria-label="Name" placeholder={body.tool === 'google' ? `${code}-${project.slug}-eng@hack4impact.org` : `${code}-${project.slug}-eng`}
              value={body.name ?? ''} onChange={(e) => setBody({ ...body, name: e.target.value })} />
          </>
        ) : (
          <Select aria-label="Resource" value={body.resourceId ?? ''} onChange={(e) => setBody({ ...body, resourceId: e.target.value || null, tool: null, name: null })}>
            <Select.Option value="">Choose…</Select.Option>
            {existing.filter((r) => !attached.has(r.id)).map((r) => (
              <Select.Option key={r.id} value={r.id}>{TOOL_NAME[r.tool] ?? r.tool}: {resourceName(r.tool, r.name)}{r.projects.length ? ` (${r.projects.join(', ')})` : ''}</Select.Option>
            ))}
          </Select>
        )}
      </div>
      <div className="box-row inline-form">
        <AccessFields value={body} onChange={setBody} roles={roles} />
        <Button variant="primary" disabled={mode === 'new' ? !body.name : !body.resourceId}
          onClick={async () => { if (await onAdd({ ...body, tags: body.tags.filter(Boolean) })) { setBody(empty); setOpen(false) } }}>Add</Button>
        <Button onClick={() => setOpen(false)}>Cancel</Button>
      </div>
    </section>
  )
}

function Details({ project, code, edit, apply }: {
  project: ProjectDetail; code: string; edit: boolean
  apply: (body: { name: string; slug: string | null; type: string | null; tags: string[]; termId: string | null; partner: string | null; startsOn: string | null; endsOn: string | null }) => Promise<boolean>
}) {
  const [editing, setEditing] = useState(false)
  const [terms, setTerms] = useState<TermOption[]>([])
  const [form, setForm] = useState({ name: '', type: '', tags: '', termId: '', partner: '' })
  useEffect(() => {
    if (editing) void api.GET('/api/chapters/{code}/terms', { params: { path: { code } } }).then(({ data }) => setTerms(data ?? []))
  }, [editing, code])
  const start = () => {
    setForm({ name: project.name, type: project.type ?? '', tags: project.tags.join(', '), termId: project.termId ?? '', partner: project.partner ?? '' })
    setEditing(true)
  }
  if (editing) {
    return (
      <div className="pane-section form-grid" style={{ paddingTop: 0 }}>
        <div className="pane-label">Details</div>
        <label>Name <TextInput value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label>Partner <TextInput value={form.partner} onChange={(e) => setForm({ ...form, partner: e.target.value })} /></label>
        <label>Semester
          <Select value={form.termId} onChange={(e) => setForm({ ...form, termId: e.target.value })}>
            <Select.Option value="">None</Select.Option>
            {terms.map((t) => <Select.Option key={t.id} value={t.id}>{t.label}</Select.Option>)}
          </Select>
        </label>
        <label>Type <TextInput value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })} /></label>
        <label>Tags <TextInput value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} /></label>
        <div style={{ display: 'flex', gap: 8 }}>
          <Button variant="primary" onClick={async () => {
            const ok = await apply({
              name: form.name, slug: null, type: form.type || null, tags: form.tags.split(',').map((t) => t.trim()).filter(Boolean),
              termId: form.termId || null, partner: form.partner || null, startsOn: project.startsOn, endsOn: project.endsOn,
            })
            if (ok) setEditing(false)
          }}>Save</Button>
          <Button onClick={() => setEditing(false)}>Cancel</Button>
        </div>
      </div>
    )
  }
  return (
    <div className="pane-section" style={{ paddingTop: 0 }}>
      <div className="pane-label">Details</div>
      <dl className="facts">
        <dt>Short name</dt><dd>{code}-{project.slug}</dd>
        <dt>Partner</dt><dd>{project.partner ?? '—'}</dd>
        <dt>Semester</dt><dd>{project.term ?? '—'}</dd>
        <dt>Type</dt><dd>{project.type ?? '—'}</dd>
        <dt>Tags</dt><dd>{project.tags.length ? project.tags.map((t) => `#${t}`).join(' ') : '—'}</dd>
      </dl>
      {edit && <Button size="small" onClick={start}>Edit details</Button>}
    </div>
  )
}

function NotionText({ project, code }: { project: ProjectDetail; code: string }) {
  const n = project.notion
  const settings = <Link to={`/chapters/${code}/settings`}>Notion routes</Link>
  if (n.status === 'has_page') return <div>{n.title}</div>
  if (n.status === 'not_set_up') return <div className="muted">The chapter has no {settings} yet, so there's nowhere to put “{n.title}”.</div>
  if (n.status === 'no_route') return <div className="muted">No route matches this project's type or tags and there's no default. Check the {settings}.</div>
  return (
    <div>
      “{n.title}” will be created under <strong>{n.parentPath ?? 'a page not checked yet'}</strong>
      <div className="muted">{n.matchedBy === 'default' ? 'Default route' : `Route: ${n.matchedBy}`} · created when writes are switched on</div>
    </div>
  )
}
