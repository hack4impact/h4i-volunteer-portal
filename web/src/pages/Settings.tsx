import { Banner, Button, Select, Spinner, TextInput } from '@primer/react'
import { useEffect, useState } from 'react'
import { api, reason, type NotionPageRef, type NotionSettingsView } from '../api/client'
import { Status } from '../components/Status'

type RouteForm = { kind: string; value: string; parent: string }

/** A page's latest check, in words. */
function Check({ page, configured }: { page: NotionPageRef | null | undefined; configured: boolean }) {
  if (!page) return null
  if (page.error) return <Status tone="danger">{page.error}</Status>
  if (page.path) return <Status tone="ok">{page.path}</Status>
  return <Status tone="off">{configured ? 'Not checked yet' : 'Not checked: no Notion integration is set up'}</Status>
}

/**
 * Chapter settings: Notion routes (PRD). Where each project's Notion page goes: ordered rules from a tag or type to a
 * parent page, a default, the template page and the title pattern. Saving checks every page the integration can reach.
 */
export function Settings({ code }: { code: string }) {
  const [view, setView] = useState<NotionSettingsView | null>(null)
  const [form, setForm] = useState({ teamspaceId: '', defaultParent: '', template: '', titlePattern: '{project} ({semester})' })
  const [routes, setRoutes] = useState<RouteForm[]>([])
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  function load(v: NotionSettingsView) {
    setView(v)
    setForm({ teamspaceId: v.teamspaceId ?? '', defaultParent: v.defaultParent?.pageId ?? '', template: v.template?.pageId ?? '', titlePattern: v.titlePattern })
    setRoutes(v.routes.map((r) => ({ kind: r.kind, value: r.value, parent: r.parent.pageId })))
  }
  useEffect(() => {
    void api.GET('/api/chapters/{code}/notion', { params: { path: { code } } }).then(({ data }) => data && load(data))
  }, [code])

  async function save(e: React.FormEvent) {
    e.preventDefault()
    const { data, error: failed, response } = await api.PUT('/api/chapters/{code}/notion', {
      params: { path: { code } },
      body: {
        teamspaceId: form.teamspaceId || null, defaultParent: form.defaultParent || null, template: form.template || null,
        titlePattern: form.titlePattern, routes: routes.filter((r) => r.value || r.parent),
      },
    })
    if (!data) return setError(reason(failed, response, 'The settings could not be saved'))
    setError(null)
    setSaved(true)
    load(data)
  }

  if (!view) return <div className="center"><Spinner aria-label="Loading settings" /></div>
  const edit = view.canEdit
  const move = (i: number, by: number) => {
    const next = [...routes]
    const [r] = next.splice(i, 1)
    next.splice(i + by, 0, r)
    setRoutes(next)
  }
  const setRoute = (i: number, patch: Partial<RouteForm>) => setRoutes(routes.map((r, j) => (j === i ? { ...r, ...patch } : r)))

  return (
    <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
      <form className="box" aria-labelledby="notion-routes" onSubmit={save}>
        <div className="box-head" id="notion-routes">Notion routes</div>
        <div className="box-row muted">
          Where each project's Notion page is created. The first route whose tag or type matches the project wins; otherwise the default page.
          Changing a route only affects new projects.
        </div>
        {error && <div className="box-row"><Banner title="Not saved" description={error} variant="critical" /></div>}
        {saved && !error && view.problems === 0 && <div className="box-row"><Banner title="Saved" description={view.configured ? 'Every page was reachable.' : 'Saved. Pages will be checked once a Notion integration is set up.'} variant="success" /></div>}
        {view.problems > 0 && <div className="box-row"><Banner title="Some pages can't be reached" description="Share them with the portal's Notion integration, or pick other pages. Projects can't get pages there until it's fixed." variant="warning" /></div>}
        <div className="box-row form-grid">
          <label>
            Default page
            <TextInput disabled={!edit} value={form.defaultParent} onChange={(e) => setForm({ ...form, defaultParent: e.target.value })} placeholder="Notion link or page ID" />
            <Check page={view.defaultParent} configured={view.configured} />
          </label>
          <label>
            Template page
            <TextInput disabled={!edit} value={form.template} onChange={(e) => setForm({ ...form, template: e.target.value })} placeholder="The page new project pages are copied from" />
            <Check page={view.template} configured={view.configured} />
          </label>
          <label>
            Title pattern
            <TextInput disabled={!edit} value={form.titlePattern} onChange={(e) => setForm({ ...form, titlePattern: e.target.value })} />
            <span className="muted">{'{project}'}, {'{semester}'} and {'{chapter}'} are filled in, e.g. “RISE DC (Fall 2026)”.</span>
          </label>
          <label>
            Teamspace ID
            <TextInput disabled={!edit} value={form.teamspaceId} onChange={(e) => setForm({ ...form, teamspaceId: e.target.value })} placeholder="Used for the Notion admin queue" />
          </label>
        </div>
        {routes.map((r, i) => {
          const checked = view.routes.find((v) => v.parent.pageId === r.parent)?.parent
          return (
            <div className="box-row inline-form" key={i} aria-label={`Route ${i + 1}`} role="group">
              <span className="muted">{i + 1}.</span>
              <Select aria-label="Match" disabled={!edit} value={r.kind} onChange={(e) => setRoute(i, { kind: e.target.value })}>
                <Select.Option value="tag">Tag</Select.Option>
                <Select.Option value="type">Type</Select.Option>
              </Select>
              <TextInput aria-label="Value" disabled={!edit} value={r.value} onChange={(e) => setRoute(i, { value: e.target.value })} placeholder="lts" />
              <span>→</span>
              <TextInput aria-label="Page" disabled={!edit} value={r.parent} onChange={(e) => setRoute(i, { parent: e.target.value })} placeholder="Notion link or page ID" style={{ minWidth: 280 }} />
              {edit && (
                <>
                  <Button size="small" variant="invisible" disabled={i === 0} onClick={() => move(i, -1)} aria-label="Move up">↑</Button>
                  <Button size="small" variant="invisible" disabled={i === routes.length - 1} onClick={() => move(i, 1)} aria-label="Move down">↓</Button>
                  <Button size="small" variant="invisible" onClick={() => setRoutes(routes.filter((_, j) => j !== i))}>Remove</Button>
                </>
              )}
              <div style={{ flexBasis: '100%' }}><Check page={checked} configured={view.configured} /></div>
            </div>
          )
        })}
        {edit && (
          <div className="box-row" style={{ display: 'flex', gap: 8 }}>
            <Button onClick={() => setRoutes([...routes, { kind: 'tag', value: '', parent: '' }])}>Add route</Button>
            <Button type="submit" variant="primary">Save and check</Button>
          </div>
        )}
      </form>
    </div>
  )
}
