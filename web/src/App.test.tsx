import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

/** Answers API calls from a table of URL path (or "METHOD path", which wins) → [status, body]. */
function api(routes: Record<string, [number, unknown]>) {
  vi.stubGlobal('fetch', vi.fn(async (input: Request | string) => {
    const path = new URL(typeof input === 'string' ? input : input.url, 'http://localhost').pathname
    const method = typeof input === 'string' ? 'GET' : input.method
    const [status, body] = routes[`${method} ${path}`] ?? routes[path] ?? [404, {}]
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  }))
}

const me = (chapters: unknown[]) => ({ email: 'lena@hack4impact.org', name: 'Lena Lead', personId: 'p1', nationalAdmin: false, chapters })
const umd = { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', role: 'lead' }

afterEach(() => {
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
})

describe('App', () => {
  it('asks a signed-out visitor to sign in with Google', async () => {
    api({ '/api/me': [401, {}] })
    render(<App />)
    expect(await screen.findByRole('button', { name: 'Sign in with Google' })).toBeInTheDocument()
  })

  it('explains a refused account after Google sends it back', async () => {
    window.history.pushState({}, '', '/?signin=denied')
    api({ '/api/me': [401, {}] })
    render(<App />)
    expect(await screen.findByText(/Use your Hack4Impact Google account/)).toBeInTheDocument()
  })

  it('tells a signed-in person without a role that they have no chapter access', async () => {
    api({ '/api/me': [200, me([])] })
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'No chapter access yet' })).toBeInTheDocument()
  })

  it("takes a lead to their chapter, and the members tab shows status as words and filters", async () => {
    api({
      '/api/me': [200, me([umd])],
      '/api/chapters/umd': [200, { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 1, liveProjects: 1, leads: 1 }, registrationLink: 'https://join.hack4impact.org/umd' }],
      '/api/chapters/umd/sync': [200, {
        tools: [{ tool: 'slack', status: 'paused', mode: 'dry_run', ranAt: '2026-10-10T12:00:00Z', error: null, adds: 1, changes: 0, removals: 0, drift: 1, unmatchedAccounts: 0, missingResources: 0 }],
        changes: [
          { kind: 'add', tool: 'slack', resource: '#umd-general', person: 'Lena Lead', accountId: 'U1', fromAccess: null, toAccess: 'write' },
          { kind: 'drift', tool: 'slack', resource: '#umd-general', person: 'Alan Alumnus', accountId: 'U2', fromAccess: 'write', toAccess: null },
        ],
      }],
      '/api/chapters/umd/members': [200, [
        { personId: 'p1', name: 'Lena Lead', email: 'lena@hack4impact.org', status: 'active', kind: 'student', chapterRole: 'lead', title: null, projects: [], accounts: [{ tool: 'google', state: 'confirmed' }], claimed: true },
        { personId: 'p2', name: 'Alan Alumnus', email: null, status: 'alumni', kind: 'student', chapterRole: null, title: null, projects: ['RISE DC Portal'], accounts: [], claimed: false },
      ]],
    })
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Hack4Impact UMD' })).toBeInTheDocument()
    expect(screen.getByText('Registration link')).toHaveAttribute('href', 'https://join.hack4impact.org/umd')
    const sync = await screen.findByRole('region', { name: 'Sync (dry run)' })
    expect(within(sync).getByText('Paused')).toBeInTheDocument() // a paused tool says so in words
    expect(within(sync).getByText(/1 to add, 1 drift/)).toBeInTheDocument()
    expect(within(sync).getByText('Not granted by the portal')).toBeInTheDocument()

    await userEvent.click(within(screen.getByRole('navigation', { name: 'Chapter sections' })).getByRole('link', { name: /Members/ }))
    const table = await screen.findByRole('table', { name: 'Members' })
    expect(within(table).getByText('Alumni')).toBeInTheDocument() // status in words, not just a colored dot
    expect(within(table).getByText('RISE DC Portal')).toBeInTheDocument()

    await userEvent.type(screen.getByRole('textbox', { name: 'Filter members' }), 'lena')
    await waitFor(() => expect(within(table).queryByText('Alan Alumnus')).not.toBeInTheDocument())
    expect(screen.getByText('1 of 2')).toBeInTheDocument()
  })

  it('shows the adoption report, the people behind a resource, and lets a lead link an unmatched channel', async () => {
    window.history.pushState({}, '', '/chapters/umd/adoption')
    const report = (state: string) => ({
      grandfatheredUntil: null,
      canEdit: true,
      projects: [{ id: 'pr1', name: 'RISE DC', slug: 'rise-dc', status: 'active' }],
      scans: [{ tool: 'slack', status: 'completed', ranAt: '2026-10-10T12:00:00Z', error: null, resourcesFound: 6, coversChapter: true }],
      resources: [
        { id: 'r1', tool: 'slack', name: 'umd-food-bank', state: 'matched', target: null, projectId: null, projectName: null, suggestedSlug: 'food-bank', matchMethod: 'convention', archived: false, gone: false, snapshotAt: '2026-10-10T12:00:00Z', expected: 0, grandfathered: 1, unknownAccounts: 0, wouldAdd: 0 },
        ...(state === 'linked' ? [{ id: 'r2', tool: 'slack', name: 'random', state: 'linked', target: 'chapter_members', projectId: null, projectName: null, suggestedSlug: null, matchMethod: 'manual', archived: false, gone: false, snapshotAt: null, expected: 0, grandfathered: 0, unknownAccounts: 0, wouldAdd: null }] : []),
      ],
    })
    api({
      '/api/me': [200, me([umd])],
      '/api/chapters/umd': [200, { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 1, liveProjects: 1, leads: 1 }, registrationLink: 'https://join.hack4impact.org/umd' }],
      '/api/chapters/umd/members': [200, []],
      '/api/chapters/umd/sync': [200, { tools: [], changes: [] }],
      '/api/chapters/umd/adoption': [200, report('matched')],
      '/api/chapters/umd/adoption/resources/r1': [200, [{ personId: 'p1', name: 'Lena Lead', login: 'lena', verdict: 'grandfathered', matchedBy: 'id', access: 'write' }]],
      '/api/adoption/unmatched': [200, [{ id: 'r2', tool: 'slack', name: 'random', archived: false }]],
      '/api/chapters/umd/adoption/resources/r2': [200, report('linked')],
    })
    render(<App />)
    expect(await screen.findByText('#umd-food-bank')).toBeInTheDocument()
    expect(screen.getByText(/Needs a decision · no project "food-bank" yet/)).toBeInTheDocument() // status in words

    await userEvent.click(screen.getByRole('button', { name: 'Show people' }))
    const people = await screen.findByRole('table', { name: 'People' })
    expect(within(people).getByText('Grandfathered')).toBeInTheDocument()

    const unmatched = screen.getByRole('region', { name: 'Not matched to any chapter' })
    await userEvent.click(within(unmatched).getByRole('button', { name: 'Link to this chapter' }))
    await userEvent.click(within(unmatched).getByRole('button', { name: 'Link' }))
    const post = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => r.method === 'POST')!
    expect(new URL(post.url).pathname).toBe('/api/chapters/umd/adoption/resources/r2')
    expect(await post.clone().json()).toEqual({ action: 'link', target: 'chapter_members', projectId: null })
    expect(await screen.findByText('#random')).toBeInTheDocument()
    expect(screen.getByText(/Linked by hand · chapter members/)).toBeInTheDocument()
  })

  it('lets a lead create a project and shows its resources, team and planned changes', async () => {
    window.history.pushState({}, '', '/chapters/umd/projects')
    const resource = (id: string, tool: string, name: string, gated: boolean) => ({
      resourceId: id, tool, name, state: 'to_create', audience: 'team', roleId: null, role: null, access: 'write', requiresAgreement: gated,
      tags: [], sharedWith: [], existsInTool: tool === 'slack', getting: gated ? 0 : 1, awaitingAgreement: gated ? 1 : 0,
    })
    const project = {
      id: 'pr1', name: 'RISE DC', slug: 'rise-dc', status: 'draft', type: null, tags: ['lts'], termId: null, term: 'Fall 2026', partner: 'RISE DC Inc',
      startsOn: null, endsOn: null, agreementsSigned: 0, canEdit: true,
      members: [{ personId: 'p2', name: 'Ada Lovelace', email: null, status: 'active', roleId: null, role: null, isLead: false, agreementStatus: 'pending' }],
      resources: [resource('r1', 'slack', 'umd-rise-dc', false), resource('r2', 'github', 'umd-rise-dc', true)],
      notion: { status: 'planned', title: 'RISE DC (Fall 2026)', pageId: null, parentPageId: 'x', parentPath: 'Hack4Impact-UMD / Long Term Success', matchedBy: 'tag lts' },
      preview: { people: 1, grants: 1, awaitingAgreement: 1, asIfActive: true },
      planned: [{ kind: 'create_resource', tool: 'slack', resource: 'umd-rise-dc', person: null, accountId: null, fromAccess: null, toAccess: null }],
      lastDryRunAt: '2026-10-10T12:00:00Z', plannedOutOfDate: true,
      activity: [{ at: '2026-10-10T12:00:00Z', actor: 'Lena Lead', action: 'project.create', detail: 'Created RISE DC with 4 standard resources' }],
    }
    api({
      '/api/me': [200, me([umd])],
      '/api/chapters/umd': [200, { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 1, liveProjects: 0, leads: 1 }, registrationLink: 'https://join.hack4impact.org/umd', notionProblems: 0 }],
      '/api/chapters/umd/members': [200, [{ personId: 'p3', name: 'Alan Alumnus', email: null, status: 'alumni', kind: 'student', chapterRole: null, title: null, projects: [], accounts: [], claimed: false }]],
      '/api/chapters/umd/sync': [200, { tools: [], changes: [] }],
      '/api/chapters/umd/terms': [200, [{ id: 't1', label: 'Fall 2026' }]],
      '/api/project-roles': [200, [{ id: 'role1', name: 'Developer', isLead: false }]],
      'GET /api/chapters/umd/projects': [200, []],
      'POST /api/chapters/umd/projects': [201, project],
      '/api/chapters/umd/projects/rise-dc': [200, project],
      '/api/chapters/umd/projects/rise-dc/members': [200, project],
      '/api/chapters/umd/sync/run': [202, {}],
    })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'New project' }))
    await userEvent.type(screen.getByRole('textbox', { name: /^Name/ }), 'RISE DC')
    expect(screen.getByText(/umd-rise-dc\. Can't be changed later/)).toBeInTheDocument() // the short name it derives
    await userEvent.click(screen.getByRole('button', { name: 'Create draft' }))

    expect(await screen.findByRole('heading', { name: 'RISE DC' })).toBeInTheDocument()
    expect(screen.getByText(/Nobody gets access from a draft/)).toBeInTheDocument()
    const github = screen.getByRole('region', { name: 'GitHub' })
    expect(within(github).getByText(/Whole team · write · after agreement/)).toBeInTheDocument()
    expect(within(github).getByText('To be created')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Slack' })).getByText(/already has something called umd-rise-dc/)).toBeInTheDocument()
    expect(screen.getByText(/will be created under/)).toBeInTheDocument()
    expect(screen.getByText('Hack4Impact-UMD / Long Term Success')).toBeInTheDocument()
    expect(screen.getByText(/The project changed since then/)).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: /^Team/ })).getByText('Agreement not signed')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Person to add' }), 'p3')
    await userEvent.click(within(screen.getByRole('region', { name: /^Team/ })).getByRole('button', { name: 'Add' }))
    const calls = () => vi.mocked(fetch).mock.calls.map(([r]) => r as Request)
    const add = calls().find((r) => r.method === 'POST' && r.url.endsWith('/members'))!
    expect(await add.clone().json()).toEqual({ personId: 'p3', roleId: null })

    await userEvent.click(screen.getByRole('button', { name: 'Run dry run' }))
    await waitFor(() => expect(calls().some((r) => r.method === 'POST' && r.url.endsWith('/sync/run'))).toBe(true))
    expect(screen.getByRole('button', { name: 'Running…' })).toBeDisabled()
  })

  it('saves Notion routes in order and shows each page check in words', async () => {
    window.history.pushState({}, '', '/chapters/umd/settings')
    const view = {
      configured: true, teamspaceId: null, titlePattern: '{project} ({semester})', problems: 1, canEdit: true,
      defaultParent: { pageId: 'aaaa', path: 'Hack4Impact-UMD', error: null, checkedAt: '2026-10-10T12:00:00Z' },
      template: null,
      routes: [{ kind: 'tag', value: 'lts', parent: { pageId: 'bbbb', path: null, error: 'The integration can\'t see this page', checkedAt: '2026-10-10T12:00:00Z' } }],
    }
    api({
      '/api/me': [200, me([umd])],
      '/api/chapters/umd': [200, { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 1, liveProjects: 0, leads: 1 }, registrationLink: 'https://join.hack4impact.org/umd', notionProblems: 1 }],
      '/api/chapters/umd/members': [200, []],
      '/api/chapters/umd/sync': [200, { tools: [], changes: [] }],
      '/api/chapters/umd/notion': [200, view],
    })
    render(<App />)
    expect(await screen.findByText("The integration can't see this page")).toBeInTheDocument()
    expect(screen.getByText('Hack4Impact-UMD')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Add route' }))
    const second = screen.getByRole('group', { name: 'Route 2' })
    await userEvent.selectOptions(within(second).getByRole('combobox', { name: 'Match' }), 'type')
    await userEvent.type(within(second).getByRole('textbox', { name: 'Value' }), 'new-build')
    await userEvent.type(within(second).getByRole('textbox', { name: 'Page' }), 'cccc')
    await userEvent.click(within(second).getByRole('button', { name: 'Move up' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save and check' }))
    const put = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => r.method === 'PUT')!
    expect((await put.clone().json()).routes).toEqual([{ kind: 'type', value: 'new-build', parent: 'cccc' }, { kind: 'tag', value: 'lts', parent: 'bbbb' }])
  })
})
