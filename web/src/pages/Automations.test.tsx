import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from '../App'

/** Answers API calls from a table of URL path (or "METHOD path", which wins) → [status, body]. */
function api(routes: Record<string, [number, unknown]>) {
  vi.stubGlobal('fetch', vi.fn(async (input: Request | string) => {
    const path = new URL(typeof input === 'string' ? input : input.url, 'http://localhost').pathname
    const method = typeof input === 'string' ? 'GET' : input.method
    const [status, body] = routes[`${method} ${path}`] ?? routes[path] ?? [404, {}]
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  }))
}

const calls = () => vi.mocked(fetch).mock.calls.map(([r]) => r as Request)
const at = '2026-10-12T12:00:00Z'
const me = { email: 'lena@hack4impact.org', name: 'Lena Lead', personId: 'p1', nationalAdmin: false, chapters: [{ id: 'c1', code: 'umd', name: 'Hack4Impact UMD', role: 'lead' }] }
const chapter = { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 0, liveProjects: 0, leads: 1 }, registrationLink: 'x', notionProblems: 0 }

const input = (over: object = {}) => ({
  name: 'Everyone in #general', description: null, enabled: true, access: 'read', tools: [],
  who: { statuses: ['active'], everyone: true, chapterRoles: [], titles: [], projectRoles: [], people: [] },
  projects: { scope: 'none', ids: [], tags: [] }, resources: { scope: 'tags', ids: [], tags: ['general'] }, ...over,
})
const rule = (over: object = {}) => ({
  id: 'r1', chapterCode: 'umd', name: 'Everyone in #general', description: null, enabled: true, input: input(),
  sentence: 'Everyone → Read on resources tagged #general', reach: { people: 12, resources: 1 }, updatedBy: 'Lena Lead', updatedAt: at, warnings: [], pendingChanges: [], ...over,
})
const options = { projectRoles: [{ id: 'tl', name: 'Tech Lead', detail: 'lead role', tool: null }], titles: [], projects: [], resources: [], tags: ['eng', 'general'], people: [] }
const page = (over: object = {}) => ({
  rules: [rule()], canEdit: true, options,
  national: [rule({ id: 'n1', chapterCode: null, name: 'Leads channel', sentence: 'Chapter leads and co-leads → Read on resources tagged #leads', reach: { people: 2, resources: 1 } })],
  ...over,
})
const preview = (over: object = {}) => ({ adds: 0, removes: 0, accessChanges: 0, people: 0, resources: 0, afterAgreement: 0, perPerson: [], ...over })
const ada = { personId: 'a', name: 'Ada Test', joins: [], leaves: [], gains: [], loses: [], afterAgreement: [] }

function chapterRoutes(extra: Record<string, [number, unknown]> = {}) {
  api({
    '/api/me': [200, me],
    '/api/chapters/umd': [200, chapter],
    '/api/chapters/umd/members': [200, []],
    '/api/chapters/umd/sync': [200, { tools: [], changes: [] }],
    '/api/chapters/umd/rules': [200, page()],
    ...extra,
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  sessionStorage.clear()
  window.history.pushState({}, '', '/')
})

describe('Automations', () => {
  it('lists a chapter’s rules as sentences with their reach, and the national defaults read-only', async () => {
    window.history.pushState({}, '', '/chapters/umd/automations')
    chapterRoutes()
    render(<App />)
    const rules = await screen.findByRole('list', { name: 'Rules' })
    expect(within(rules).getByText('Everyone in #general')).toBeInTheDocument()
    expect(within(rules).getByText('Read on resources tagged #general')).toBeInTheDocument()
    expect(within(rules).getByText(/Reaches 12 people · 1 resource/)).toBeInTheDocument()
    expect(within(rules).getByRole('button', { name: 'Everyone in #general', pressed: true })).toBeInTheDocument()
    const national = screen.getByRole('list', { name: 'National defaults' })
    expect(within(national).getByText('Leads channel')).toBeInTheDocument()
    expect(within(national).queryByRole('button', { pressed: true })).toBeNull() // leads can't change national defaults
  })

  it('previews a new rule as it’s written, and saves it', async () => {
    window.history.pushState({}, '', '/chapters/umd/automations')
    chapterRoutes({
      'POST /api/chapters/umd/rules/preview': [200, {
        sentence: 'Tech Leads on any project → Admin on resources tagged #eng', warnings: ['No resource is tagged #eng yet; the rule will cover ones that are'],
        preview: preview({ adds: 2, people: 2, resources: 1, perPerson: [{ ...ada, gains: ['umd-eng (GitHub)'] }] }),
      }],
      'POST /api/chapters/umd/rules': [201, page({ rules: [rule(), rule({ id: 'r2', name: 'Tech leads get the repos' })] })],
    })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'New rule' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.type(within(dialog).getByRole('textbox', { name: /^Name/ }), 'Tech leads get the repos')
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'Tech Leads on any project' }))
    await userEvent.selectOptions(within(dialog).getByRole('combobox', { name: 'Access' }), 'admin')
    await userEvent.click(within(dialog).getByRole('button', { name: '#eng' })) // a tag the chapter already uses

    expect(await within(dialog).findAllByText('Adds 2 people to 1 resource')).not.toHaveLength(0)
    expect(within(dialog).getByText(/No resource is tagged #eng yet/)).toBeInTheDocument()
    expect(within(dialog).getByTitle('GitHub: umd-eng')).toBeInTheDocument()
    const sent = await (calls().filter((r) => r.url.endsWith('/rules/preview')).at(-1)!).clone().json()
    expect(sent.input.who).toMatchObject({ everyone: false, projectRoles: ['tl'] })
    expect(sent.input).toMatchObject({ access: 'admin', resources: { scope: 'tags', tags: ['eng'] } })

    await userEvent.click(within(dialog).getByRole('button', { name: 'Create rule' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByText('Tech leads get the repos')).toBeInTheDocument()
  })

  it('shows what turning a rule off takes back before doing it', async () => {
    window.history.pushState({}, '', '/chapters/umd/automations')
    chapterRoutes({
      'POST /api/chapters/umd/rules/preview': [200, { sentence: null, warnings: [], preview: preview({ removes: 12, people: 12, resources: 1, perPerson: [{ ...ada, loses: ['umd-general (Slack)'] }] }) }],
      'POST /api/chapters/umd/rules/r1/enabled': [200, page({ rules: [rule({ enabled: false, reach: { people: 0, resources: 0 } })] })],
    })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Everyone in #general', pressed: true }))
    const dialog = await screen.findByRole('dialog', { name: /Turn off/ })
    expect(await within(dialog).findByText('Removes 12 grants from 12 people')).toBeInTheDocument()
    expect(within(dialog).getByText('Loses')).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Turn off' }))
    expect(await screen.findByText(/Off: gives nothing/)).toBeInTheDocument()
    const post = calls().find((r) => r.url.endsWith('/rules/r1/enabled'))!
    expect(await post.clone().json()).toEqual({ enabled: false })
  })

  it('sends rule changes to the open draft', async () => {
    sessionStorage.setItem('h4i-draft:umd', 'd1')
    window.history.pushState({}, '', '/chapters/umd/automations')
    chapterRoutes({
      '/api/chapters/umd/rules': [200, page({ rules: [rule({ pendingChanges: ['Turn off the rule “Everyone in #general”'] })] })],
      '/api/chapters/umd/drafts/d1': [200, { draft: { id: 'd1', name: 'Fall rules', status: 'draft', createdBy: 'Lena Lead', createdAt: at, changes: 1, scheduledFor: null, releasedAt: null, releasedBy: null, releaseError: null }, canEdit: true, changes: [], conflicts: [], preview: preview() }],
    })
    render(<App />)
    expect(await screen.findByText(/Planning in the draft/)).toBeInTheDocument()
    expect(await screen.findByText('Changed in this draft')).toBeInTheDocument()
    const list = calls().find((r) => new URL(r.url).pathname === '/api/chapters/umd/rules')!
    expect(new URL(list.url).searchParams.get('draft')).toBe('d1')
  })

  it('explains why a member has each resource', async () => {
    window.history.pushState({}, '', '/chapters/umd/members')
    chapterRoutes({
      '/api/chapters/umd/members': [200, [{ personId: 'a', name: 'Ada Test', email: 'ada@hack4impact.org', claimed: true, status: 'active', chapterRole: null, title: null, projects: ['RISE DC'], accounts: [] }]],
      '/api/chapters/umd/members/a/access': [200, [
        { tool: 'slack', name: 'umd-general', url: null, state: 'ready', access: 'read', reasons: ['Rule “Everyone in #general”'] },
        { tool: 'github', name: 'umd-rise-dc', url: null, state: 'after_agreement', access: 'write', reasons: ['After they sign the RISE DC agreement'] },
      ]],
    })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Why Ada Test has their access' }))
    const dialog = await screen.findByRole('dialog', { name: /Ada Test’s access/ })
    expect(await within(dialog).findByText('Rule “Everyone in #general”')).toBeInTheDocument()
    expect(within(dialog).getByText('After they sign the RISE DC agreement')).toBeInTheDocument()
  })
})
