import { Banner, Button, Select, Spinner, TextInput } from '@primer/react'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { api, reason, type ProjectSummary, type TermOption } from '../api/client'
import { PROJECT_STATUS, Status } from '../components/Status'

/** The chapter's projects (build plan step 8), live ones first, and the form to start a new one. */
export function Projects({ code, canEdit }: { code: string; canEdit: boolean }) {
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null)
  const [creating, setCreating] = useState(false)
  useEffect(() => {
    setProjects(null)
    void api.GET('/api/chapters/{code}/projects', { params: { path: { code } } }).then(({ data }) => setProjects(data ?? []))
  }, [code])

  if (!projects) return <div className="center"><Spinner aria-label="Loading projects" /></div>
  return (
    <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
      {creating && <NewProject code={code} onCancel={() => setCreating(false)} />}
      <section className="box" aria-labelledby="projects-title">
        <div className="box-head" id="projects-title">
          Projects
          <span className="muted">{projects.length}</span>
          {canEdit && !creating && (
            <Button size="small" variant="primary" style={{ marginLeft: 'auto' }} onClick={() => setCreating(true)}>New project</Button>
          )}
        </div>
        {projects.length === 0 && <div className="box-row muted">No projects yet.{canEdit ? ' Start one with New project.' : ''}</div>}
        {projects.map((p) => {
          const [label, tone] = PROJECT_STATUS[p.status] ?? [p.status, 'off']
          return (
            <div className="box-row" key={p.id} style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 16px', alignItems: 'baseline' }}>
              <Link to={`/chapters/${code}/projects/${p.slug}`}><strong>{p.name}</strong></Link>
              <Status tone={tone}>{label}</Status>
              <span className="muted">
                {[p.term, p.partner, p.type, ...p.tags.map((t) => `#${t}`)].filter(Boolean).join(' · ')}
              </span>
              <span className="muted" style={{ marginLeft: 'auto' }}>{p.members} members · {p.resources} resources</span>
            </div>
          )
        })}
      </section>
    </div>
  )
}

function NewProject({ code, onCancel }: { code: string; onCancel: () => void }) {
  const navigate = useNavigate()
  const [terms, setTerms] = useState<TermOption[]>([])
  const [form, setForm] = useState({ name: '', slug: '', type: '', tags: '', termId: '', partner: '' })
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  useEffect(() => {
    void api.GET('/api/chapters/{code}/terms', { params: { path: { code } } }).then(({ data }) => setTerms(data ?? []))
  }, [code])
  const slug = form.slug || form.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setForm({ ...form, [k]: e.target.value })

  async function create(e: React.FormEvent) {
    e.preventDefault()
    setSaving(true)
    const { data, error: failed, response } = await api.POST('/api/chapters/{code}/projects', {
      params: { path: { code } },
      body: {
        name: form.name, slug: form.slug || null, type: form.type || null, tags: form.tags.split(',').map((t) => t.trim()).filter(Boolean),
        termId: form.termId || null, partner: form.partner || null, startsOn: null, endsOn: null,
      },
    })
    setSaving(false)
    if (!data) return setError(reason(failed, response, 'The project could not be created'))
    navigate(`/chapters/${code}/projects/${data.slug}`)
  }

  return (
    <form className="box" aria-labelledby="new-project" onSubmit={create}>
      <div className="box-head" id="new-project">New project</div>
      <div className="box-row form-grid">
        {error && <Banner title="Not created" description={error} variant="critical" />}
        <label>Name <TextInput required value={form.name} onChange={set('name')} placeholder="RISE DC Portal" /></label>
        <label>
          Short name <TextInput value={form.slug} onChange={set('slug')} placeholder={slug || 'rise-dc'} />
          <span className="muted">Names its channel, team and group: {code}-{slug || '…'}. Can't be changed later.</span>
        </label>
        <label>Partner <TextInput value={form.partner} onChange={set('partner')} placeholder="Organization name" /></label>
        <label>
          Semester
          <Select value={form.termId} onChange={set('termId')}>
            <Select.Option value="">None</Select.Option>
            {terms.map((t) => <Select.Option key={t.id} value={t.id}>{t.label}</Select.Option>)}
          </Select>
        </label>
        <label>Type <TextInput value={form.type} onChange={set('type')} placeholder="new-build, lts" /></label>
        <label>Tags <TextInput value={form.tags} onChange={set('tags')} placeholder="client, eng" /><span className="muted">Comma-separated. Notion routes use type and tags.</span></label>
        <div style={{ display: 'flex', gap: 8 }}>
          <Button type="submit" variant="primary" disabled={saving || !form.name.trim()}>Create draft</Button>
          <Button onClick={onCancel}>Cancel</Button>
        </div>
        <span className="muted">New projects start as drafts: nobody gets access until you activate them. The standard channel, team, group and vault collection are added for you.</span>
      </div>
    </form>
  )
}
