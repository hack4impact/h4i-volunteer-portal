import { KebabHorizontalIcon } from '@primer/octicons-react'
import { ActionList, ActionMenu, Banner, Button, Checkbox, Dialog, IconButton, Label, Select, Spinner, TextInput } from '@primer/react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import {
  api, reason, type ChapterResourceOption, type ProjectDetail, type ProjectResourceRow, type ActivityItem, type ProjectRoleOption, type TeamCandidate, type TermOption,
} from '../api/client'
import { CHANGE_LABEL, PROJECT_STATUS, STATUS_LABEL, Status, TOOL_NAME, resourceName } from '../components/Status'
import { ToolIcon } from '../components/ToolIcon'

const TOOL_ORDER = ['slack', 'google', 'github', 'vaultwarden']
/** Change pills (design guide): green add, red remove, blue change. */
const CHANGE_VARIANT: Record<string, 'success' | 'danger' | 'accent' | 'attention' | 'secondary'> = {
  add: 'success', create_resource: 'success', remove: 'danger', change: 'accent', drift: 'attention', missing_resource: 'attention',
}
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
export function ProjectPage({ code, slug }: { code: string; slug: string }) {
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
      <div style={{ gridColumn: '1 / -1', display: 'flex', flexWrap: 'wrap', gap: '8px 16px', alignItems: 'flex-end' }}>
        <div>
          <div className="eyebrow"><Link to={`/chapters/${code}/projects`}>Projects</Link></div>
          <div className="project-title-row">
            <h2 className="project-title">{project.name}</h2>
            <Status tone={statusTone}>{statusLabel}</Status>
          </div>
        </div>
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
      {project.pendingChanges.length > 0 && (
        <section className="box" aria-labelledby="pending-title" style={{ gridColumn: '1 / -1' }}>
          <div className="box-head" id="pending-title">In this draft <span className="Counter">{project.pendingChanges.length}</span></div>
          <div className="box-row"><ol className="pending-changes">{project.pendingChanges.map((c, i) => <li key={i}>{c}</li>)}</ol></div>
        </section>
      )}

      <main style={{ display: 'grid', gap: 24, alignContent: 'start' }}>
        <ResourceGroups project={project} tools={tools} roles={roles} edit={edit} code={code}
          onAdd={(body) => apply(api.POST('/api/chapters/{code}/projects/{slug}/resources', { ...path, body }))}
          onSave={(id, body) => apply(api.PUT('/api/chapters/{code}/projects/{slug}/resources/{resourceId}', { params: { path: { code, slug, resourceId: id } }, body }))}
          onDetach={(r) => window.confirm(
            `Remove ${resourceName(r.tool, r.name)} from ${project.name}?\n\n` + (r.state === 'exists'
              ? `The team stops getting it through this project at the next sync; anyone who also gets it another way keeps it. It isn't deleted from ${TOOL_NAME[r.tool] ?? r.tool}.`
              : `It hasn't been created in ${TOOL_NAME[r.tool] ?? r.tool} yet, so it won't be.`),
          ) && apply(api.DELETE('/api/chapters/{code}/projects/{slug}/resources/{resourceId}', { params: { path: { code, slug, resourceId: r.resourceId } } }))}
        />

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
          {project.planned.length > 0 && (
            <table className="list-table" aria-label="Planned changes">
              <colgroup><col style={{ width: 150 }} /><col /><col /><col className="wide-only" style={{ width: 130 }} /></colgroup>
              <thead><tr><th scope="col">Change</th><th scope="col">Person</th><th scope="col">Resource</th><th scope="col" className="wide-only">Access</th></tr></thead>
              <tbody>
                {project.planned.slice(0, 50).map((c, i) => (
                  <tr key={i}>
                    <td><Label variant={CHANGE_VARIANT[c.kind] ?? 'secondary'}>{CHANGE_LABEL[c.kind] ?? c.kind}</Label></td>
                    <td>{c.person ?? <span className="muted">—</span>}</td>
                    <td><span className="tool-name"><ToolIcon tool={c.tool} />{resourceName(c.tool, c.resource)}</span></td>
                    <td className="wide-only">{c.fromAccess && c.toAccess ? `${c.fromAccess} → ${c.toAccess}` : (c.toAccess ?? c.fromAccess ?? '—')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {project.planned.length > 50 && <div className="box-row muted">And {project.planned.length - 50} more.</div>}
        </section>
      </main>

      <aside>
        {gated && project.members.length > 0 && <AgreementCard project={project} />}
        <TeamPane project={project} roles={roles} edit={project.canEditTeam && project.status !== 'closed'} leadsView={project.canEdit} gated={gated} apply={apply} code={code} slug={slug} />
        <Details project={project} code={code} edit={edit} apply={(body) => apply(api.PUT('/api/chapters/{code}/projects/{slug}', { ...path, body }))} />
        <div className="pane-section" style={{ borderBottom: 0 }}>
          <div className="pane-label">Recent activity</div>
          <ActivityFeed key={`${code}/${slug}`} code={code} slug={slug} items={project.activity} more={project.activityMore} />
        </div>
        <div className="dots" aria-hidden="true" />
      </aside>
    </div>
  )
}

type Apply = (call: Promise<{ data?: ProjectDetail; error?: unknown; response: Response }>) => Promise<boolean>

/**
 * The team. [edit]: may change it (the chapter's leads, and the project's own leads, PRD project lead role).
 * [leadsView]: the chapter's leads see agreement PDFs.
 */
function Team({ project, roles, edit, leadsView, gated, apply, code, slug }: {
  project: ProjectDetail; roles: ProjectRoleOption[]; edit: boolean; leadsView: boolean; gated: boolean; apply: Apply; code: string; slug: string
}) {
  // Shown in the team dialog: the table and the add-member row, without a box of its own.
  const [personId, setPersonId] = useState('')
  const [roleId, setRoleId] = useState('')
  const [people, setPeople] = useState<TeamCandidate[]>([])
  useEffect(() => {
    if (edit) void api.GET('/api/chapters/{code}/projects/{slug}/candidates', { params: { path: { code, slug } } }).then(({ data }) => setPeople(data ?? []))
  }, [code, slug, edit, project.members.length])
  const onTeam = new Set(project.members.map((m) => m.personId))
  const candidates = people.filter((m) => !onTeam.has(m.personId))
  const member = (personId: string) => ({ params: { path: { code, slug, personId } } })
  return (
    <div className="team-editor">
      {project.members.length === 0 && <div className="muted">No members yet.</div>}
      {project.members.length > 0 && (
        <table className="list-table" aria-label="Team members">
          <colgroup><col /><col style={{ width: 150 }} />{gated && <col className="wide-only" style={{ width: 180 }} />}{edit && <col style={{ width: 84 }} />}</colgroup>
          <thead>
            <tr>
              <th scope="col">Member</th><th scope="col">Role</th>
              {gated && <th scope="col" className="wide-only">Project agreement</th>}
              {edit && <th scope="col"><span className="visually-hidden">Actions</span></th>}
            </tr>
          </thead>
          <tbody>
            {project.members.map((m) => (
              <tr key={m.personId}>
                <td>
                  <strong>{m.name}</strong>
                  {m.status !== 'active' && <> <Label variant="secondary">{STATUS_LABEL[m.status] ?? m.status}</Label></>}
                </td>
                <td>
                  {edit ? (
                    <Select aria-label={`${m.name}'s role`} size="small" block value={m.roleId ?? ''}
                      onChange={(e) => apply(api.PUT('/api/chapters/{code}/projects/{slug}/members/{personId}', { ...member(m.personId), body: { roleId: e.target.value || null } }))}>
                      <Select.Option value="">No role</Select.Option>
                      {roles.map((r) => <Select.Option key={r.id} value={r.id}>{r.name}</Select.Option>)}
                    </Select>
                  ) : (m.role ?? <span className="muted">No role</span>)}
                </td>
                {gated && <td className="wide-only"><AgreementState m={m} canEdit={leadsView} /></td>}
                {edit && (
                  <td className="num">
                    <Button size="small" variant="invisible" onClick={() => apply(api.DELETE('/api/chapters/{code}/projects/{slug}/members/{personId}', member(m.personId)))}>Remove</Button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {edit && (
        <div className="inline-form team-add">
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
    </div>
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

const GROUP: [string, string, string][] = [
  ['slack', 'Slack channels', 'Slack channel'],
  ['github', 'GitHub teams', 'GitHub team'],
  ['vaultwarden', 'Vaultwarden collections', 'Vaultwarden collection'],
  ['notion', 'Notion page', 'Notion page'],
  ['google', 'Google groups', 'Google group'],
]

/** How the sync stands for one resource, in words; a dot only when someone has to act or something failed. */
function syncState(r: ProjectResourceRow, draft: boolean): [string, 'ok' | 'warn' | 'off' | 'danger'] {
  if (r.state === 'archived') return ['Archived', 'off']
  if (r.state === 'to_create') return [draft ? 'Created when the project starts' : 'To be created', 'off']
  const s = r.sync
  if (s.failing) return [`${s.failing} failing`, 'danger']
  if (s.waitingOnMember) return [r.tool === 'vaultwarden' ? `${s.waitingOnMember} to confirm` : `${s.waitingOnMember} ${s.waitingOnMember === 1 ? 'invite' : 'invites'} pending`, 'warn']
  if (s.manualTask) return [`${s.manualTask} waiting on national`, 'warn']
  if (s.pending) return [`${s.pending} to add`, 'off']
  return ['In sync', 'ok']
}

/**
 * The project's resources, one box per tool (Slack channels, GitHub teams, …), each with its own Add: who gets each
 * resource, whether it needs the agreement, and how the sync stands.
 */
function ResourceGroups({ project, tools, roles, edit, code, onAdd, onSave, onDetach }: {
  project: ProjectDetail; tools: string[]; roles: ProjectRoleOption[]; edit: boolean; code: string
  onAdd: (b: ResourceBody) => Promise<boolean>; onSave: (resourceId: string, b: ResourceBody) => Promise<boolean>; onDetach: (r: ProjectResourceRow) => void
}) {
  const [editing, setEditing] = useState<{ id: string; body: ResourceBody } | null>(null)
  const [adding, setAdding] = useState<string | null>(null)
  const draft = project.preview.asIfActive
  const order = [...GROUP.map(([t]) => t), ...tools.filter((t) => !GROUP.some(([g]) => g === t))]
  return (
    <>
      {order.map((tool) => {
        const [, plural] = GROUP.find(([t]) => t === tool) ?? [tool, TOOL_NAME[tool] ?? tool, tool]
        if (tool === 'notion') return <NotionBox key={tool} project={project} code={code} edit={edit} />
        const rows = project.resources.filter((r) => r.tool === tool)
        return (
          <section className="box" key={tool} aria-labelledby={`tool-${tool}`}>
            <div className="box-head" id={`tool-${tool}`}>
              <span className="tool-name"><ToolIcon tool={tool} />{plural}</span> <span className="muted">{rows.length}</span>
              {edit && adding !== tool && <Button size="small" style={{ marginLeft: 'auto' }} onClick={() => setAdding(tool)}>Add</Button>}
            </div>
            {adding === tool && <AddResource code={code} project={project} roles={roles} tool={tool} onAdd={onAdd} onClose={() => setAdding(null)} />}
            {rows.length === 0 && adding !== tool && <div className="box-row muted">None yet.</div>}
            {rows.map((r) => {
              const [state, tone] = syncState(r, draft)
              const meta = [audienceText(r), r.access[0]!.toUpperCase() + r.access.slice(1), ...r.tags.map((t) => `#${t}`)].join(' · ')
              return (
                <div className="box-row res-row" key={r.resourceId} aria-label={`${TOOL_NAME[r.tool] ?? r.tool} ${resourceName(r.tool, r.name)}`} role="group">
                  <div className="res-main">
                    {r.url ? <a className="res-name" href={r.url} target="_blank" rel="noreferrer">{resourceName(r.tool, r.name)}</a> : <span className="res-name">{resourceName(r.tool, r.name)}</span>}
                    <div className="muted list-sub">
                      {meta}
                      {r.requiresAgreement && <> · <Label variant="attention" size="small">Needs agreement</Label>{r.awaitingAgreement > 0 && ` ${r.awaitingAgreement} ${draft ? 'would wait' : 'waiting'} to sign`}</>}
                      {r.sharedWith.length > 0 && <> · shared with {r.sharedWith.join(', ')}</>}
                    </div>
                    {r.existsInTool && (
                      <div className="list-sub">
                        <Status tone="warn">{TOOL_NAME[r.tool] ?? r.tool} already has something called {r.name}</Status>
                        <span className="muted"> (found by the adoption scan). If it's this project's, link it on the Adoption tab instead of creating a new one.</span>
                      </div>
                    )}
                    {editing?.id === r.resourceId && (
                      <div className="inline-form" style={{ marginTop: 8 }}>
                        <AccessFields value={editing.body} onChange={(body) => setEditing({ id: r.resourceId, body })} roles={roles} />
                        <Button size="small" variant="primary" onClick={async () => { if (await onSave(r.resourceId, { ...editing.body, tags: editing.body.tags.filter(Boolean) })) setEditing(null) }}>Save</Button>
                        <Button size="small" onClick={() => setEditing(null)}>Cancel</Button>
                      </div>
                    )}
                  </div>
                  <div className="res-state"><Status tone={tone}>{state}</Status></div>
                  {edit && (
                    <ActionMenu>
                      <ActionMenu.Anchor><IconButton icon={KebabHorizontalIcon} variant="invisible" size="small" aria-label={`Actions for ${resourceName(r.tool, r.name)}`} /></ActionMenu.Anchor>
                      <ActionMenu.Overlay>
                        <ActionList>
                          <ActionList.Item onSelect={() => setEditing({ id: r.resourceId, body: { resourceId: null, tool: null, name: null, audience: r.audience, roleId: r.roleId, access: r.access, requiresAgreement: r.requiresAgreement, tags: r.tags } })}>
                            Change who gets it
                          </ActionList.Item>
                          <ActionList.Item variant="danger" onSelect={() => onDetach(r)}>Remove from project</ActionList.Item>
                        </ActionList>
                      </ActionMenu.Overlay>
                    </ActionMenu>
                  )}
                </div>
              )
            })}
          </section>
        )
      })}
    </>
  )
}

/** The project's Notion page: made from the chapter's Notion routes, not added by hand. */
function NotionBox({ project, code, edit }: { project: ProjectDetail; code: string; edit: boolean }) {
  const n = project.notion
  const routes = <Link to={`/chapters/${code}/settings`}>Notion routes</Link>
  const name = n.status === 'has_page' ? n.title : n.parentPath ? `${n.parentPath} / ${n.title}` : n.title
  const [state, tone]: [string, 'ok' | 'warn' | 'off'] =
    n.status === 'has_page' ? ['Created from template', 'ok']
      : n.status === 'planned' ? ['Created when writes are switched on', 'off']
        : ['Nowhere to create it', 'warn']
  return (
    <section className="box" aria-labelledby="tool-notion">
      <div className="box-head" id="tool-notion">
        <span className="tool-name"><ToolIcon tool="notion" />Notion page</span> <span className="muted">1</span>
        {edit && <span className="muted" style={{ marginLeft: 'auto', fontWeight: 400 }}>From the chapter’s {routes}</span>}
      </div>
      <div className="box-row res-row" role="group" aria-label={`Notion ${name}`}>
        <div className="res-main">
          <span className="res-name">{name}</span>
          <div className="muted list-sub">
            {n.status === 'not_set_up' ? <>The chapter has no {routes} yet</>
              : n.status === 'no_route' ? <>No route matches this project’s type or tags, and there’s no default</>
                : n.matchedBy === 'default' ? 'Default route' : `Route: ${n.matchedBy}`}
          </div>
        </div>
        <div className="res-state"><Status tone={tone}>{state}</Status></div>
      </div>
    </section>
  )
}

function AddResource({ code, project, roles, tool, onAdd, onClose }: {
  code: string; project: ProjectDetail; roles: ProjectRoleOption[]; tool: string; onAdd: (b: ResourceBody) => Promise<boolean>; onClose: () => void
}) {
  const empty: ResourceBody = { resourceId: null, tool, name: '', audience: 'team', roleId: null, access: 'write', requiresAgreement: tool === 'github' || tool === 'vaultwarden', tags: [] }
  const open = true
  const [mode, setMode] = useState<'new' | 'existing'>('new')
  const [body, setBody] = useState<ResourceBody>(empty)
  const [existing, setExisting] = useState<ChapterResourceOption[]>([])
  useEffect(() => {
    if (open) void api.GET('/api/chapters/{code}/resources', { params: { path: { code } } }).then(({ data }) => setExisting(data ?? []))
  }, [open, code])
  const attached = new Set(project.resources.map((r) => r.resourceId))
  return (
    <div className="add-resource" role="group" aria-label={`Add a ${TOOL_NAME[tool] ?? tool} resource`}>
      <div className="box-row inline-form">
        <Select aria-label="New or existing" value={mode} onChange={(e) => { setMode(e.target.value as 'new' | 'existing'); setBody(empty) }}>
          <Select.Option value="new">A new one</Select.Option>
          <Select.Option value="existing">One this chapter already has</Select.Option>
        </Select>
        {mode === 'new' ? (
          <>
            <TextInput aria-label="Name" placeholder={body.tool === 'google' ? `${code}-${project.slug}-eng@hack4impact.org` : `${code}-${project.slug}-eng`}
              value={body.name ?? ''} onChange={(e) => setBody({ ...body, name: e.target.value })} />
          </>
        ) : (
          <Select aria-label="Resource" value={body.resourceId ?? ''} onChange={(e) => setBody({ ...body, resourceId: e.target.value || null, tool: null, name: null })}>
            <Select.Option value="">Choose…</Select.Option>
            {existing.filter((r) => !attached.has(r.id) && r.tool === tool).map((r) => (
              <Select.Option key={r.id} value={r.id}>{TOOL_NAME[r.tool] ?? r.tool}: {resourceName(r.tool, r.name)}{r.projects.length ? ` (${r.projects.join(', ')})` : ''}</Select.Option>
            ))}
          </Select>
        )}
      </div>
      <div className="box-row inline-form">
        <AccessFields value={body} onChange={setBody} roles={roles} />
        <Button variant="primary" disabled={mode === 'new' ? !body.name : !body.resourceId}
          onClick={async () => { if (await onAdd({ ...body, tags: body.tags.filter(Boolean) })) { setBody(empty); onClose() } }}>Add</Button>
        <Button onClick={onClose}>Cancel</Button>
      </div>
    </div>
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
  return (
    <div className="pane-section">
      <div className="pane-label">Details</div>
      <dl className="facts">
        <dt>Short name</dt><dd>{code}-{project.slug}</dd>
        <dt>Partner</dt><dd>{project.partner ?? '—'}</dd>
        <dt>Semester</dt><dd>{project.term ?? '—'}</dd>
        <dt>Type</dt><dd>{project.type ?? '—'}</dd>
        <dt>Tags</dt><dd>{project.tags.length ? project.tags.map((t) => `#${t}`).join(' ') : '—'}</dd>
      </dl>
      {edit && <button type="button" className="timeline-toggle" onClick={start}>Edit details</button>}
      {editing && (
        <Dialog title="Edit details" subtitle={project.name} width="medium" onClose={() => setEditing(false)}
          footerButtons={[
            { buttonType: 'default', content: 'Cancel', onClick: () => setEditing(false) },
            {
              buttonType: 'primary', content: 'Save', onClick: async () => {
                const ok = await apply({
                  name: form.name, slug: null, type: form.type || null, tags: form.tags.split(',').map((t) => t.trim()).filter(Boolean),
                  termId: form.termId || null, partner: form.partner || null, startsOn: project.startsOn, endsOn: project.endsOn,
                })
                if (ok) setEditing(false)
              },
            },
          ]}>
          <div className="form-grid">
            <label>Name <TextInput block value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
            <label>Partner <TextInput block value={form.partner} onChange={(e) => setForm({ ...form, partner: e.target.value })} /></label>
            <label>Semester
              <Select block value={form.termId} onChange={(e) => setForm({ ...form, termId: e.target.value })}>
                <Select.Option value="">None</Select.Option>
                {terms.map((t) => <Select.Option key={t.id} value={t.id}>{t.label}</Select.Option>)}
              </Select>
            </label>
            <label>Type <TextInput block value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })} /></label>
            <label>Tags <TextInput block value={form.tags} onChange={(e) => setForm({ ...form, tags: e.target.value })} placeholder="comma-separated" /></label>
          </div>
        </Dialog>
      )}
    </div>
  )
}

/** Mockup "Project agreement": how many signed, and who's still waiting for the gated repos and vault collections. */
function AgreementCard({ project }: { project: ProjectDetail }) {
  const total = project.members.length
  const waiting = project.members.filter((m) => m.agreementStatus === 'pending').map((m) => m.name)
  const names = waiting.length <= 3 ? waiting : [...waiting.slice(0, 2), `${waiting.length - 2} more`]
  const list = names.length > 1 ? `${names.slice(0, -1).join(', ')} and ${names.at(-1)}` : names[0]
  return (
    <div className="side-card" aria-label="Project agreement">
      <div className="pane-label">Project agreement</div>
      <strong>{project.agreementsSigned} of {total} signed</strong>
      <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={project.agreementsSigned}>
        <span style={{ width: `${total ? (100 * project.agreementsSigned) / total : 0}%` }} />
      </div>
      <div className="muted list-sub">
        {waiting.length === 0 ? 'Everyone has signed.' : `${list} ${waiting.length === 1 ? 'gets' : 'get'} repo and vault access after signing.`}
      </div>
    </div>
  )
}

const initialsOf = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('')

/** The team in the sidebar (mockup): the first few, the rest and all changes in a dialog. */
function TeamPane(props: { project: ProjectDetail; roles: ProjectRoleOption[]; edit: boolean; leadsView: boolean; gated: boolean; apply: Apply; code: string; slug: string }) {
  const { project, edit } = props
  const [open, setOpen] = useState(false)
  const shown = project.members.slice(0, 6)
  return (
    <div className="pane-section">
      <div className="pane-label">Team <span className="pane-count">{project.members.length}</span></div>
      {project.members.length === 0 && <div className="muted">No members yet.</div>}
      <ul className="team-list" aria-label="Team">
        {shown.map((m) => (
          <li key={m.personId}>
            <span className="initials small" aria-hidden="true">{initialsOf(m.name)}</span>
            <span className="team-name">{m.name}{m.status === 'alumni' ? <span className="muted"> · alumni</span> : ''}</span>
            <span className="muted">{m.role ?? ''}</span>
          </li>
        ))}
      </ul>
      {(edit || project.members.length > shown.length) && (
        <button type="button" className="timeline-toggle" onClick={() => setOpen(true)}>
          {edit ? 'Manage team' : `All ${project.members.length} members`}
        </button>
      )}
      {open && (
        <Dialog title="Team" width="xlarge" onClose={() => setOpen(false)}
          subtitle={`${project.name} · ${project.members.length} ${project.members.length === 1 ? 'member' : 'members'}${props.gated ? ` · ${project.agreementsSigned} signed the project agreement` : ''}`}
          footerButtons={[{ buttonType: 'default', content: 'Done', onClick: () => setOpen(false) }]}>
          <Team {...props} />
        </Dialog>
      )}
    </div>
  )
}

/** A member's project agreement in words: where it is in Documenso, and the signed PDF for leads. */
function AgreementState({ m, canEdit }: { m: ProjectDetail['members'][number]; canEdit: boolean }) {
  const a = m.agreement
  const day = (d?: string | null) => (d ? new Date(d).toLocaleDateString() : '')
  if (m.agreementStatus === 'waived') return <Status tone="ok">Agreement waived</Status>
  if (m.agreementStatus === 'signed') {
    return (
      <span style={{ display: 'inline-flex', gap: 8, alignItems: 'center' }}>
        <Status tone="ok">Agreement signed{a?.signedAt ? ` ${day(a.signedAt)}` : ''}</Status>
        {canEdit && a?.hasPdf && <a href={`/api/agreements/${a.id}/pdf`}>PDF</a>}
      </span>
    )
  }
  if (a?.status === 'declined') return <Status tone="danger">Agreement declined{a.declineReason ? `: ${a.declineReason}` : ''}</Status>
  if (a?.status === 'sent') {
    return <Status tone="off">Agreement sent {day(a.sentAt)}{a.reminders ? ` · reminded ${a.reminders}×` : ''}</Status>
  }
  return a?.error ? <Status tone="danger">Agreement not sent: {a.error}</Status> : <Status tone="off">Agreement not sent yet</Status>
}

/** "Today, 6:31 PM", "Yesterday", "Sep 28", or with the year when it isn't this year's. */
function when(iso: string) {
  const d = new Date(iso)
  const today = new Date()
  const days = Math.round((new Date(today.toDateString()).getTime() - new Date(d.toDateString()).getTime()) / 86_400_000)
  if (days === 0) return `Today, ${d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}`
  if (days === 1) return 'Yesterday'
  return d.toLocaleDateString([], d.getFullYear() === today.getFullYear() ? { month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric', year: 'numeric' })
}

/**
 * The project's activity as a timeline: edits (with who made them), what the sync did, agreements. The latest few
 * first; "Show all activity" loads older ones; an item with more behind it (a big sync) opens to show it.
 */
function ActivityFeed({ code, slug, items, more }: { code: string; slug: string; items: ActivityItem[]; more: boolean }) {
  const [all, setAll] = useState<ActivityItem[] | null>(null)
  const [hasMore, setHasMore] = useState(more)
  const [open, setOpen] = useState<Set<string>>(new Set())
  const [loading, setLoading] = useState(false)
  const shown = all ?? items
  const key = (a: ActivityItem, i: number) => `${a.at}:${i}`

  async function older() {
    setLoading(true)
    const before = shown.at(-1)?.at
    const { data } = await api.GET('/api/chapters/{code}/projects/{slug}/activity', { params: { path: { code, slug }, query: { limit: 20, ...(before ? { before } : {}) } } })
    setLoading(false)
    if (!data) return
    setAll([...shown, ...data.items])
    setHasMore(data.more)
  }

  if (shown.length === 0) return <div className="muted">Nothing yet.</div>
  return (
    <>
      <ol className="timeline" aria-label="Activity">
        {shown.map((a, i) => {
          const k = key(a, i)
          const expanded = open.has(k)
          return (
            <li key={k} className={`timeline-item timeline-${a.kind}`}>
              <span className="timeline-dot" aria-hidden="true" />
              <div className="timeline-text">{a.text}</div>
              {a.details.length > 0 && (
                <button type="button" className="timeline-toggle" aria-expanded={expanded}
                  onClick={() => setOpen((o) => { const n = new Set(o); if (n.has(k)) n.delete(k); else n.add(k); return n })}>
                  {expanded ? 'Hide details' : `Show ${a.details.length === 1 ? 'details' : `all ${a.details.length}`}`}
                </button>
              )}
              {expanded && <ul className="timeline-details">{a.details.map((d) => <li key={d}>{d}</li>)}</ul>}
              <div className="timeline-meta">{when(a.at)}{a.actor ? ` · ${a.actor}` : a.kind === 'sync' ? ' · sync' : ''}</div>
            </li>
          )
        })}
      </ol>
      <div className="timeline-more">
        {hasMore && <button type="button" className="timeline-toggle" disabled={loading} onClick={() => void older()}>{all ? 'Show older' : 'Show all activity'}</button>}
        {all && <button type="button" className="timeline-toggle" onClick={() => { setAll(null); setHasMore(more); setOpen(new Set()) }}>Show less</button>}
      </div>
    </>
  )
}
