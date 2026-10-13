import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TOOLS } from '../test/tools'
import { App } from '../App'

/** Answers API calls from a table of URL path (or "METHOD path", which wins) → [status, body]. */
function api(routes: Record<string, [number, unknown]>) {
  vi.stubGlobal('fetch', vi.fn(async (input: Request | string) => {
    const path = new URL(typeof input === 'string' ? input : input.url, 'http://localhost').pathname
    const method = typeof input === 'string' ? 'GET' : input.method
    const [status, body] = routes[`${method} ${path}`] ?? routes[path] ?? (path === '/api/tools' ? [200, TOOLS] : [404, {}])
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  }))
}

const me = { email: 'nora@hack4impact.org', name: 'Nora National', personId: 'p1', nationalAdmin: true, chapters: [{ id: 'cn', code: 'national', name: 'Hack4Impact National', role: 'national' }] }
const chapter = { id: 'cn', code: 'national', name: 'Hack4Impact National', status: 'active', kind: 'national', role: 'national', stats: { activeMembers: 3, alumni: 1, liveProjects: 1, leads: 2 }, registrationLink: 'x', notionProblems: 0 }
const project = {
  id: 'pr1', name: 'Chapter Starter Kit', slug: 'chapter-starter-kit', status: 'active', type: null, tags: [], termId: null, term: null, partner: null,
  startsOn: null, endsOn: null, agreementsSigned: 0, canEdit: true, canEditTeam: true, pendingChanges: [], members: [], resources: [], notion: { status: 'not_configured', title: 'Chapter Starter Kit', pageId: null, parentPageId: null, parentPath: null, matchedBy: null },
  preview: { people: 0, grants: 0, awaitingAgreement: 0, asIfActive: false }, planned: [], lastDryRunAt: null, plannedOutOfDate: false, activity: [], activityMore: false,
}
// The whole org: more than the picker lists without a search.
const people = Array.from({ length: 60 }, (_, i) => ({ personId: `p${i}`, name: `Person ${String(i).padStart(2, '0')}`, status: 'active', chapters: [i % 2 ? 'UMD' : 'GT'] }))

function routes() {
  api({
    '/api/me': [200, me],
    '/api/chapters/national': [200, chapter],
    '/api/chapters/national/members': [200, []],
    '/api/chapters/national/sync': [200, { tools: [], changes: [] }],
    '/api/chapters/national/terms': [200, []],
    '/api/project-roles': [200, []],
    '/api/chapters/national/projects/chapter-starter-kit': [200, project],
    '/api/chapters/national/projects/chapter-starter-kit/activity': [200, { items: [], more: false }],
    '/api/chapters/national/projects/chapter-starter-kit/candidates': [200, people],
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

describe('National projects', () => {
  it('shows national’s own chapter with projects but no members, applications or chapter rules', async () => {
    window.history.pushState({}, '', '/chapters/national')
    routes()
    render(<App />)
    expect(await screen.findByText(/National’s own projects/)).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Chapter sections' })
    expect(within(nav).getByRole('link', { name: /Projects/ })).toBeInTheDocument()
    for (const name of [/Members/, /Approvals/, /Automations/, /Adoption/]) expect(within(nav).queryByRole('link', { name })).toBeNull()
    expect(screen.getByText('People on them')).toBeInTheDocument()
  })

  it('finds anyone in the org for the team, by name or chapter', async () => {
    window.history.pushState({}, '', '/chapters/national/projects/chapter-starter-kit')
    routes()
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'Manage team' }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByRole('option', { name: 'Person 01 · UMD' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Search people' }), 'gt')
    const picker = within(dialog).getByRole('combobox', { name: 'Person to add' })
    expect(within(picker).getByRole('option', { name: /30 found/ })).toBeInTheDocument()
    expect(within(picker).queryByRole('option', { name: 'Person 01 · UMD' })).toBeNull()
  })
})
