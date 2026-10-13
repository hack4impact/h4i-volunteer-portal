import { AlertIcon, ArrowRightIcon, KebabHorizontalIcon, PlusIcon, XIcon } from '@primer/octicons-react'
import { ActionList, ActionMenu, Banner, Button, Checkbox, Dialog, IconButton, Label, Radio, Select, Spinner, TextInput, ToggleSwitch } from '@primer/react'
import { useEffect, useRef, useState } from 'react'
import { api, reason, type DraftPreview, type RuleInput, type RulePreview, type RulePreviewInput, type RuleView, type RulesPage } from '../api/client'
import { useActiveDraft } from '../api/drafts'
import { TOOL_NAME } from '../components/Status'
import { resourceName, resourceTools } from '../api/tools'
import { ToolIcon } from '../components/ToolIcon'
import { ResourceChip, previewSentence } from './Drafts'

/** Whose rules: a chapter's (with the national defaults read-only) or the national defaults themselves. */
export type RuleScope = { kind: 'chapter'; code: string } | { kind: 'national' }

type Call<T> = Promise<{ data?: T; error?: unknown; response: Response }>

/** The rules API for a scope; in a chapter the draft middleware adds `?draft=` while a draft is open. */
function rulesApi(scope: RuleScope) {
  if (scope.kind === 'chapter') {
    const code = scope.code
    return {
      load: (): Call<RulesPage> => api.GET('/api/chapters/{code}/rules', { params: { path: { code } } }),
      create: (body: RuleInput): Call<RulesPage> => api.POST('/api/chapters/{code}/rules', { params: { path: { code } }, body }),
      update: (id: string, body: RuleInput): Call<RulesPage> => api.PUT('/api/chapters/{code}/rules/{id}', { params: { path: { code, id } }, body }),
      setEnabled: (id: string, enabled: boolean): Call<RulesPage> => api.POST('/api/chapters/{code}/rules/{id}/enabled', { params: { path: { code, id } }, body: { enabled } }),
      remove: (id: string): Call<RulesPage> => api.DELETE('/api/chapters/{code}/rules/{id}', { params: { path: { code, id } } }),
      preview: (body: RulePreviewInput): Call<RulePreview> => api.POST('/api/chapters/{code}/rules/preview', { params: { path: { code } }, body }),
    }
  }
  return {
    load: (): Call<RulesPage> => api.GET('/api/national/rules'),
    create: (body: RuleInput): Call<RulesPage> => api.POST('/api/national/rules', { body }),
    update: (id: string, body: RuleInput): Call<RulesPage> => api.PUT('/api/national/rules/{id}', { params: { path: { id } }, body }),
    setEnabled: (id: string, enabled: boolean): Call<RulesPage> => api.POST('/api/national/rules/{id}/enabled', { params: { path: { id } }, body: { enabled } }),
    remove: (id: string): Call<RulesPage> => api.DELETE('/api/national/rules/{id}', { params: { path: { id } } }),
    preview: (body: RulePreviewInput): Call<RulePreview> => api.POST('/api/national/rules/preview', { body }),
  }
}


const EMPTY: RuleInput = {
  name: '', description: null, enabled: true, access: 'read', tools: [],
  who: { statuses: ['active'], everyone: false, chapterRoles: [], titles: [], projectRoles: [], people: [] },
  projects: { scope: 'none', ids: [], tags: [] },
  resources: { scope: 'tags', ids: [], tags: [] },
}

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`

/** "Tech leads on any project → Admin on resources tagged #eng": who, an arrow, what. */
function Sentence({ text }: { text: string }) {
  const [who, what] = text.split(' → ')
  if (!what) return <span>{text}</span>
  return <span className="rule-sentence"><span>{who}</span><ArrowRightIcon size={14} className="muted-icon" aria-label="get" /><span>{what}</span></span>
}

/**
 * Automations (build plan step 14): rules that give access by role, title or tag. A chapter's leads, co-leads and
 * national change them; everyone with a chapter role reads them, with the national defaults that apply everywhere.
 */
export function Automations({ scope }: { scope: RuleScope }) {
  const rules = rulesApi(scope)
  const [draft, version] = useActiveDraft(scope.kind === 'chapter' ? scope.code : '')
  const [page, setPage] = useState<RulesPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<RuleView | 'new' | null>(null)
  const [confirming, setConfirming] = useState<{ rule: RuleView; action: 'on' | 'off' | 'delete' } | null>(null)

  useEffect(() => {
    void rules.load().then(({ data, error: failed, response }) => (data ? setPage(data) : setError(reason(failed, response, 'Rules could not be loaded'))))
  }, [scope.kind === 'chapter' ? scope.code : 'national', draft, version])

  if (!page) return <div className="center">{error ? <Banner title="Not available" description={error} variant="critical" /> : <Spinner aria-label="Loading rules" />}</div>
  const national = scope.kind === 'national'

  // On the national page the boxes sit in that page's own column.
  return (
    <div className={national ? 'rule-boxes' : 'page-body'} style={national ? undefined : { gridTemplateColumns: '1fr' }}>
      {error && <Banner title="Not done" description={error} variant="critical" onDismiss={() => setError(null)} />}
      <section className="box" aria-labelledby="rules-title">
        <div className="box-head" id="rules-title">
          {national ? 'National defaults' : 'Rules'} <span className="Counter">{page.rules.length}</span>
          {page.canEdit && <Button size="small" leadingVisual={PlusIcon} style={{ marginLeft: 'auto' }} onClick={() => setEditing('new')}>New rule</Button>}
        </div>
        <div className="box-row muted">
          {national
            ? 'Rules for every chapter. Chapter leads see them but can’t change them.'
            : 'Rules give access automatically: people who match get the resources, and lose them again when they stop matching or the rule is turned off.'}
        </div>
        {page.rules.length === 0 && <div className="box-row muted">No rules yet.{page.canEdit ? ' Start with one like “Tech leads get admin on the #eng repositories”.' : ''}</div>}
        <ul className="adopt-list" aria-label={national ? 'National defaults' : 'Rules'}>
          {page.rules.map((r) => (
            <RuleRow key={r.id} r={r} canEdit={page.canEdit} onEdit={() => setEditing(r)} onConfirm={(action) => setConfirming({ rule: r, action })} />
          ))}
        </ul>
      </section>

      {!national && (
        <section className="box" aria-labelledby="national-title">
          <div className="box-head" id="national-title">National defaults <span className="Counter">{page.national.length}</span></div>
          <div className="box-row muted">Set by national for every chapter. They add to your rules; ask national to change them.</div>
          {page.national.length === 0 && <div className="box-row muted">None yet.</div>}
          <ul className="adopt-list" aria-label="National defaults">
            {page.national.map((r) => <RuleRow key={r.id} r={r} canEdit={false} />)}
          </ul>
        </section>
      )}

      {editing && (
        <RuleEditor scope={scope} page={page} rule={editing === 'new' ? null : editing} inDraft={!!draft && !national}
          onClose={() => setEditing(null)} onSaved={(p) => { setPage(p); setEditing(null) }} />
      )}
      {confirming && (
        <ConfirmRule scope={scope} {...confirming} inDraft={!!draft && !national} onClose={() => setConfirming(null)}
          onDone={(p, failed) => { setConfirming(null); if (p) setPage(p); setError(failed) }} />
      )}
    </div>
  )
}

function RuleRow({ r, canEdit, onEdit, onConfirm }: { r: RuleView; canEdit: boolean; onEdit?: () => void; onConfirm?: (a: 'on' | 'off' | 'delete') => void }) {
  const reach = !r.enabled ? 'Off: gives nothing'
    : r.reach.people === 0 ? 'Reaches nobody yet'
    : `Reaches ${plural(r.reach.people, 'person', 'people')} · ${plural(r.reach.resources, 'resource')}`
  return (
    <li>
      <div className="task-row rule-row">
        <div className="adopt-title">
          <div className="adopt-name">
            <span className="name-text" id={`rule-${r.id}`}>{r.name}</span>
            {r.pendingChanges.length > 0 && <Label variant="attention" title={r.pendingChanges.join('\n')}>Changed in this draft</Label>}
          </div>
          <div className={r.enabled ? '' : 'muted'}><Sentence text={r.sentence} /></div>
          <div className="adopt-meta">
            {reach}{r.description ? ` · ${r.description}` : ''}{r.updatedBy ? ` · ${r.updatedBy}, ${new Date(r.updatedAt).toLocaleDateString()}` : ''}
          </div>
          {r.warnings.map((w) => <Warning key={w} text={w} />)}
        </div>
        {canEdit && onEdit && onConfirm && (
          <span className="rule-actions">
            <ToggleSwitch size="small" checked={r.enabled} aria-labelledby={`rule-${r.id}`} onClick={() => onConfirm(r.enabled ? 'off' : 'on')} />
            <ActionMenu>
              <ActionMenu.Anchor><IconButton icon={KebabHorizontalIcon} variant="invisible" size="small" aria-label={`Actions for ${r.name}`} /></ActionMenu.Anchor>
              <ActionMenu.Overlay align="end">
                <ActionList>
                  <ActionList.Item onSelect={onEdit}>Edit</ActionList.Item>
                  <ActionList.Item variant="danger" onSelect={() => onConfirm('delete')}>Delete</ActionList.Item>
                </ActionList>
              </ActionMenu.Overlay>
            </ActionMenu>
          </span>
        )}
      </div>
    </li>
  )
}

function Warning({ text }: { text: string }) {
  return <div className="rule-warning"><AlertIcon size={12} />{text}</div>
}

/** What a preview says, in full: the counts, what's worth knowing, and each person's change. */
/**
 * What a rule change does: the totals in a sentence, anything worth knowing, and a table of who changes and how.
 * The editor shows it as its last section; turning a rule on or off and deleting it show it before confirming.
 */
function RuleResult({ preview, warnings = [] }: { preview: DraftPreview; warnings?: string[] }) {
  const [all, setAll] = useState(false)
  const people = preview.perPerson
  const shown = all ? people : people.slice(0, 6)
  return (
    <>
      <p className="rule-result-summary">{previewSentence(preview)}.</p>
      {warnings.map((w) => <Warning key={w} text={w} />)}
      {people.length > 0 && (
        <div className="rule-result-table">
          <table className="list-table" aria-label="Who it changes">
            <colgroup><col style={{ width: '32%' }} /><col /></colgroup>
            <thead><tr><th scope="col">Person</th><th scope="col">Change</th></tr></thead>
            <tbody>
              {shown.map((p) => (
                <tr key={p.personId}>
                  <td>{p.name}</td>
                  <td>
                    <div className="rule-result-change">
                      {p.gains.map((g) => <ResourceChip key={g} label={g} refs={p.resources} />)}
                      {p.afterAgreement.map((g) => <span key={g} className="rule-result-later"><ResourceChip label={g} refs={p.resources} /><span className="muted">after the agreement</span></span>)}
                      {p.loses.map((g) => <ResourceChip key={g} label={g} refs={p.resources} remove />)}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {people.length > shown.length && (
            <Button size="small" variant="invisible" onClick={() => setAll(true)}>Show all {people.length} people</Button>
          )}
        </div>
      )}
    </>
  )
}

/** Turning a rule on or off, or deleting it: what it would do first (PRD: turning it off takes back what it gave). */
function ConfirmRule({ scope, rule, action, inDraft, onClose, onDone }: {
  scope: RuleScope; rule: RuleView; action: 'on' | 'off' | 'delete'; inDraft: boolean
  onClose: () => void; onDone: (page: RulesPage | null, error: string | null) => void
}) {
  const rules = rulesApi(scope)
  const [preview, setPreview] = useState<RulePreview | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const body: RulePreviewInput = action === 'delete' ? { ruleId: rule.id, input: null, delete: true } : { ruleId: rule.id, input: { ...rule.input, enabled: action === 'on' }, delete: false }
    void rules.preview(body).then(({ data, error: failed, response }) => (data ? setPreview(data) : setError(reason(failed, response, 'No preview'))))
  }, [rule.id, action])

  async function go() {
    const { data, error: failed, response } = await (action === 'delete' ? rules.remove(rule.id) : rules.setEnabled(rule.id, action === 'on'))
    onDone(data ?? null, data ? null : reason(failed, response, 'Not done'))
  }

  const verb = action === 'on' ? 'Turn on' : action === 'off' ? 'Turn off' : 'Delete'
  return (
    <Dialog title={`${verb} “${rule.name}”?`} subtitle={<Sentence text={rule.sentence} />} width="xlarge" onClose={onClose}
      footerButtons={[
        { buttonType: 'default', content: 'Cancel', onClick: onClose },
        { buttonType: action === 'delete' ? 'danger' : 'primary', content: inDraft ? `${verb} in the draft` : verb, onClick: () => void go(), disabled: !preview },
      ]}>
      {error ? <Banner title="No preview" description={error} variant="critical" />
        : !preview ? <Spinner size="small" aria-label="Working out what it would do" />
        : (
          <div className="rule-result">
            <RuleResult preview={preview.preview} />
            {action === 'delete' && <p className="muted rule-result-note">The rule stays in the records, so past changes still say where they came from.</p>}
          </div>
        )}
    </Dialog>
  )
}

/** "a, b" ⇄ ["a", "b"], tags without their #. */
const splitTags = (s: string) => s.split(/[\s,]+/).map((t) => t.replace(/^#/, '').trim().toLowerCase()).filter(Boolean)

/** A labelled row of options: the label in its own column, the options wrapping beside it. */
function ChoiceGroup({ label, children }: { label?: string; children: React.ReactNode }) {
  return (
    <div className="rule-choice-group">
      <span className="rule-choice-label">{label}</span>
      <div className="rule-choice-options">{children}</div>
    </div>
  )
}

function toggle<T>(list: T[], item: T, on: boolean) {
  return on ? (list.includes(item) ? list : [...list, item]) : list.filter((x) => x !== item)
}

/** A rule's form, with what it would do updating as it changes. */
function RuleEditor({ scope, page, rule, inDraft, onClose, onSaved }: {
  scope: RuleScope; page: RulesPage; rule: RuleView | null; inDraft: boolean; onClose: () => void; onSaved: (p: RulesPage) => void
}) {
  const rules = rulesApi(scope)
  const national = scope.kind === 'national'
  const options = page.options
  const [form, setForm] = useState<RuleInput>(rule?.input ?? EMPTY)
  const [resourceTags, setResourceTags] = useState((rule?.input.resources.tags ?? []).join(', '))
  const [projectTags, setProjectTags] = useState((rule?.input.projects.tags ?? []).join(', '))
  const [preview, setPreview] = useState<RulePreview | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const seq = useRef(0)

  const input: RuleInput = {
    ...form,
    resources: { ...form.resources, tags: form.resources.scope === 'tags' ? splitTags(resourceTags) : [] , ids: form.resources.scope === 'ids' ? form.resources.ids : [] },
    projects: { ...form.projects, tags: form.projects.scope === 'tags' ? splitTags(projectTags) : [], ids: form.projects.scope === 'ids' ? form.projects.ids : [] },
    who: form.who.everyone ? { ...form.who, chapterRoles: [], titles: [], projectRoles: [], people: [] } : form.who,
  }
  const key = JSON.stringify(input)

  // The preview follows the form, a moment after typing stops; a stale answer is dropped.
  useEffect(() => {
    const n = ++seq.current
    const t = setTimeout(() => {
      void rules.preview({ ruleId: rule?.id ?? null, input: { ...input, name: input.name.trim() || 'Untitled rule' }, delete: false }).then(({ data, error: failed, response }) => {
        if (n !== seq.current) return
        if (data) { setPreview(data); setProblem(null) } else setProblem(reason(failed, response, 'No preview'))
      })
    }, 350)
    return () => clearTimeout(t)
  }, [key])

  async function save() {
    setSaving(true)
    const { data, error: failed, response } = await (rule ? rules.update(rule.id, input) : rules.create(input))
    setSaving(false)
    if (!data) return setSaveError(reason(failed, response, 'Not saved'))
    onSaved(data)
  }

  const set = (patch: Partial<RuleInput>) => setForm((f) => ({ ...f, ...patch }))
  const who = (patch: Partial<RuleInput['who']>) => setForm((f) => ({ ...f, who: { ...f.who, ...patch } }))
  const peopleById = new Map(options.people.map((p) => [p.id, p]))
  const resourceTagSet = new Set(splitTags(resourceTags))
  const line = problem ? problem : !preview ? 'Working out what it would do…' : previewSentence(preview.preview)

  return (
    <Dialog title={rule ? `Edit “${rule.name}”` : national ? 'New national default' : 'New rule'} subtitle={inDraft ? 'Goes into the open draft; nothing changes until it’s released.' : undefined}
      width="xlarge" onClose={onClose}
      renderFooter={() => (
        <Dialog.Footer>
          <span className={`rule-footer-line ${problem ? 'rule-problem' : ''}`} aria-live="polite">{line}</span>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" disabled={saving || !form.name.trim()} onClick={() => void save()}>{inDraft ? 'Add to draft' : rule ? 'Save' : 'Create rule'}</Button>
        </Dialog.Footer>
      )}>
      <div className="rule-form">
        {saveError && <Banner title="Not saved" description={saveError} variant="critical" onDismiss={() => setSaveError(null)} />}
        <div className="form-grid">
          <label>Name <TextInput block value={form.name} placeholder="Tech leads get the repositories" onChange={(e) => set({ name: e.target.value })} /></label>
          <label><span>Note <span className="muted">(optional, for other leads)</span></span>
            <TextInput block value={form.description ?? ''} onChange={(e) => set({ description: e.target.value || null })} />
          </label>
        </div>

        <fieldset className="rule-section">
          <legend>Who</legend>
          <label className="check"><Radio name="who" value="everyone" checked={form.who.everyone} onChange={() => who({ everyone: true })} /> Everyone {national ? 'in every chapter' : 'in the chapter'}</label>
          <label className="check"><Radio name="who" value="some" checked={!form.who.everyone} onChange={() => who({ everyone: false })} /> Only people who match any of these</label>
          {!form.who.everyone && (
            <div className="rule-choices">
              <ChoiceGroup label="Chapter role">
                {[['lead', 'Leads'], ['co_lead', 'Co-leads'], ['viewer', 'Viewers']].map(([v, l]) => (
                  <label key={v} className="check"><Checkbox checked={form.who.chapterRoles.includes(v!)} onChange={(e) => who({ chapterRoles: toggle(form.who.chapterRoles, v!, e.target.checked) })} /> {l}</label>
                ))}
              </ChoiceGroup>
              {options.projectRoles.length > 0 && (
                <ChoiceGroup label="Project role">
                  {options.projectRoles.map((r) => (
                    <label key={r.id} className="check"><Checkbox checked={form.who.projectRoles.includes(r.id)} onChange={(e) => who({ projectRoles: toggle(form.who.projectRoles, r.id, e.target.checked) })} /> {r.name}s on any project</label>
                  ))}
                </ChoiceGroup>
              )}
              {options.titles.length > 0 && (
                <ChoiceGroup label="Title">
                  {options.titles.map((t) => (
                    <label key={t} className="check"><Checkbox checked={form.who.titles.includes(t)} onChange={(e) => who({ titles: toggle(form.who.titles, t, e.target.checked) })} /> {t}</label>
                  ))}
                </ChoiceGroup>
              )}
              {!national && (
                <ChoiceGroup label="People">
                  {form.who.people.map((id) => (
                    <span key={id} className="chip">{peopleById.get(id)?.name ?? 'Someone'}
                      <IconButton icon={XIcon} size="small" variant="invisible" aria-label={`Remove ${peopleById.get(id)?.name ?? 'person'}`} onClick={() => who({ people: form.who.people.filter((p) => p !== id) })} />
                    </span>
                  ))}
                  <Select aria-label="Add a person" value="" onChange={(e) => e.target.value && who({ people: [...form.who.people, e.target.value] })}>
                    <Select.Option value="">Add a person…</Select.Option>
                    {options.people.filter((p) => !form.who.people.includes(p.id)).map((p) => (
                      <Select.Option key={p.id} value={p.id}>{p.name}{p.detail ? ` (${p.detail})` : ''}</Select.Option>
                    ))}
                  </Select>
                </ChoiceGroup>
              )}
            </div>
          )}
          <div className="rule-choices">
            <ChoiceGroup label="Applies to">
              <label className="check"><Checkbox checked={form.who.statuses.includes('active')} onChange={(e) => who({ statuses: toggle(form.who.statuses, 'active', e.target.checked) })} /> Active members</label>
              <label className="check"><Checkbox checked={form.who.statuses.includes('alumni')} onChange={(e) => who({ statuses: toggle(form.who.statuses, 'alumni', e.target.checked) })} /> Alumni</label>
            </ChoiceGroup>
          </div>
        </fieldset>

        <fieldset className="rule-section">
          <legend>Gets</legend>
          <label className="rule-inline">
            <Select aria-label="Access" value={form.access} onChange={(e) => set({ access: e.target.value })}>
              <Select.Option value="read">Read</Select.Option>
              <Select.Option value="write">Write</Select.Option>
              <Select.Option value="admin">Admin</Select.Option>
            </Select>
            <span>access to</span>
          </label>
          <label className="check"><Radio name="resources" value="tags" checked={form.resources.scope === 'tags'} onChange={() => set({ resources: { ...form.resources, scope: 'tags' } })} /> Resources tagged</label>
          {form.resources.scope === 'tags' && (
            <div className="rule-choices">
              <TextInput aria-label="Resource tags" value={resourceTags} placeholder="eng, design" onChange={(e) => setResourceTags(e.target.value)} />
              {options.tags.length > 0 && (
                <div className="rule-tag-hints">
                  {options.tags.filter((t) => !resourceTagSet.has(t)).slice(0, 12).map((t) => (
                    <button key={t} type="button" className="chip chip-button" onClick={() => setResourceTags([...resourceTagSet, t].join(', '))}>#{t}</button>
                  ))}
                </div>
              )}
            </div>
          )}
          {!national && (
            <>
              <label className="check"><Radio name="resources" value="ids" checked={form.resources.scope === 'ids'} onChange={() => set({ resources: { ...form.resources, scope: 'ids' } })} /> These resources</label>
              {form.resources.scope === 'ids' && (
                <div className="rule-choices rule-pick-list">
                  {options.resources.length === 0 && <span className="muted">The chapter has no resources yet.</span>}
                  {options.resources.map((r) => (
                    <label key={r.id} className="check">
                      <Checkbox checked={form.resources.ids.includes(r.id)} onChange={(e) => set({ resources: { ...form.resources, ids: toggle(form.resources.ids, r.id, e.target.checked) } })} />
                      {r.tool && <ToolIcon tool={r.tool} size={14} />}{resourceName(r.tool ?? '', r.name)}{r.detail && <span className="muted">{r.detail}</span>}
                    </label>
                  ))}
                </div>
              )}
            </>
          )}
        </fieldset>

        <fieldset className="rule-section">
          <legend>Where</legend>
          <Select aria-label="Which projects" value={form.projects.scope} onChange={(e) => set({ projects: { ...form.projects, scope: e.target.value } })}>
            <Select.Option value="none">{national ? 'Anywhere in each chapter' : 'Anywhere in the chapter'}</Select.Option>
            <Select.Option value="all">Only resources of live projects</Select.Option>
            <Select.Option value="tags">Only in projects tagged…</Select.Option>
            {!national && <Select.Option value="ids">Only in these projects…</Select.Option>}
          </Select>
          {form.projects.scope === 'tags' && <TextInput aria-label="Project tags" value={projectTags} placeholder="client, internal" onChange={(e) => setProjectTags(e.target.value)} />}
          {form.projects.scope === 'ids' && (
            <div className="rule-choices rule-pick-list">
              {options.projects.map((p) => (
                <label key={p.id} className="check">
                  <Checkbox checked={form.projects.ids.includes(p.id)} onChange={(e) => set({ projects: { ...form.projects, ids: toggle(form.projects.ids, p.id, e.target.checked) } })} />
                  {p.name}{p.detail && <span className="muted">{p.detail}</span>}
                </label>
              ))}
            </div>
          )}
        </fieldset>

        <fieldset className="rule-section">
          <legend>Tools <span className="muted">(none ticked: every tool)</span></legend>
          <div className="rule-choice-options">
            {resourceTools().map(({ key: t }) => (
              <label key={t} className="check"><Checkbox checked={form.tools.includes(t)} onChange={(e) => set({ tools: toggle(form.tools, t, e.target.checked) })} /><ToolIcon tool={t} size={14} />{TOOL_NAME[t] ?? t}</label>
            ))}
          </div>
        </fieldset>

        <fieldset className="rule-section rule-result" aria-live="polite">
          <legend>Result</legend>
          {preview?.sentence && <p className="rule-result-rule"><Sentence text={preview.sentence} /></p>}
          {problem ? <p className="rule-problem">{problem}</p>
            : preview ? <RuleResult preview={preview.preview} warnings={preview.warnings} />
            : <Spinner size="small" aria-label="Working out what it would do" />}
        </fieldset>
      </div>
    </Dialog>
  )
}
