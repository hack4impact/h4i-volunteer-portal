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

  it('tells someone whose Google account isn’t linked to a member that they have no access', async () => {
    api({ '/api/me': [200, { ...me([]), personId: null }] })
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'No chapter access yet' })).toBeInTheDocument()
  })

  it('shows a member without a chapter role their projects, access and why, and agreements to sign', async () => {
    api({
      '/api/me': [200, { ...me([]), email: 'marcus@hack4impact.org', name: 'Marcus Chen' }],
      '/api/me/home': [200, {
        name: 'Marcus Chen', status: 'active', chapters: [{ code: 'demo', name: 'Hack4Impact Demo University', role: null }],
        projects: [
          { chapterCode: 'demo', chapter: 'Hack4Impact Demo University', slug: 'food-rescue-network', name: 'Food Rescue Network', status: 'active', role: 'Developer', leads: false, agreementStatus: 'signed', canOpen: false },
          { chapterCode: 'demo', chapter: 'Hack4Impact Demo University', slug: 'tutor-match', name: 'Tutor Match', status: 'active', role: 'Developer', leads: false, agreementStatus: 'pending', canOpen: false },
        ],
        access: [
          { tool: 'slack', name: 'demo-general', url: 'https://slack.com/app_redirect?channel=C1', state: 'ready', access: 'write', reasons: ['Member of Hack4Impact Demo University'] },
          { tool: 'github', name: 'demo-tutor-match', url: null, state: 'after_agreement', access: 'write', reasons: ['After you sign the Tutor Match agreement'] },
        ],
        agreements: [{ id: 'a1', name: 'Project agreement', project: 'Tutor Match', status: 'sent', sentAt: '2026-10-08T12:00:00Z', signedAt: null, hasPdf: false, canSign: false }],
      }],
    })
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Marcus Chen' })).toBeInTheDocument()
    expect(screen.getByText('An agreement is waiting for your signature')).toBeInTheDocument()
    const access = screen.getByRole('list', { name: 'Your access' })
    expect(within(access).getByRole('link', { name: '#demo-general' })).toHaveAttribute('href', 'https://slack.com/app_redirect?channel=C1')
    expect(within(access).getByText('Why: Member of Hack4Impact Demo University')).toBeInTheDocument()
    expect(within(access).getByText('After you sign the agreement')).toBeInTheDocument()
    expect(screen.getByText('Waiting for you: the link is in your email')).toBeInTheDocument()
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
    const list = await screen.findByRole('list', { name: 'Resources' })
    expect(screen.getByRole('button', { name: /Needs a decision\s*1/ })).toBeInTheDocument() // state in words, as a tab with its count

    await userEvent.click(within(list).getByRole('button', { name: 'umd-food-bank' }))
    const people = await screen.findByRole('table', { name: 'People' })
    expect(within(people).getByText('Grandfathered')).toBeInTheDocument()

    const unmatched = screen.getByRole('region', { name: /^Not matched to any chapter/ })
    await userEvent.click(within(unmatched).getByRole('button', { name: 'Show' })) // collapsed until asked for
    await userEvent.click(within(unmatched).getByRole('button', { name: 'Link to this chapter' }))
    await userEvent.click(await screen.findByRole('menuitemradio', { name: 'Chapter members' }))
    const post = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => r.method === 'POST')!
    expect(new URL(post.url).pathname).toBe('/api/chapters/umd/adoption/resources/r2')
    expect(await post.clone().json()).toEqual({ action: 'link', target: 'chapter_members', projectId: null })
    expect(await within(list).findByText('random')).toBeInTheDocument()
    expect(within(list).getByText(/For chapter members · linked by hand/)).toBeInTheDocument()
  })

  it('lets a lead create a project and shows its resources, team and planned changes', async () => {
    window.history.pushState({}, '', '/chapters/umd/projects')
    const resource = (id: string, tool: string, name: string, gated: boolean) => ({
      resourceId: id, tool, name, state: 'to_create', audience: 'team', roleId: null, role: null, access: 'write', requiresAgreement: gated,
      tags: [], sharedWith: [], existsInTool: tool === 'slack', getting: gated ? 0 : 1, awaitingAgreement: gated ? 1 : 0,
    })
    const project = {
      id: 'pr1', name: 'RISE DC', slug: 'rise-dc', status: 'draft', type: null, tags: ['lts'], termId: null, term: 'Fall 2026', partner: 'RISE DC Inc',
      startsOn: null, endsOn: null, agreementsSigned: 0, canEdit: true, canEditTeam: true, pendingChanges: [],
      members: [{ personId: 'p2', name: 'Ada Lovelace', email: null, status: 'active', roleId: null, role: null, isLead: false, agreementStatus: 'pending' }],
      resources: [resource('r1', 'slack', 'umd-rise-dc', false), resource('r2', 'github', 'umd-rise-dc', true)],
      notion: { status: 'planned', title: 'RISE DC (Fall 2026)', pageId: null, parentPageId: 'x', parentPath: 'Hack4Impact-UMD / Long Term Success', matchedBy: 'tag lts' },
      preview: { people: 1, grants: 1, awaitingAgreement: 1, asIfActive: true },
      planned: [{ kind: 'create_resource', tool: 'slack', resource: 'umd-rise-dc', person: null, accountId: null, fromAccess: null, toAccess: null }],
      lastDryRunAt: '2026-10-10T12:00:00Z', plannedOutOfDate: true,
      activity: [
        { at: '2026-10-11T12:00:00Z', kind: 'sync', text: 'Updated access for 5 people', actor: null, details: ['Ada Test added to #umd-rise-dc', 'Bo Test added to #umd-rise-dc', 'Cy Test added to #umd-rise-dc', 'Di Test added to #umd-rise-dc', 'Ed Test added to #umd-rise-dc'] },
        { at: '2026-10-10T12:00:00Z', kind: 'change', text: 'Created RISE DC with 4 standard resources', actor: 'Lena Lead', details: [] },
      ],
      activityMore: true,
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
      '/api/chapters/umd/projects/rise-dc/activity': [200, { items: [{ at: '2026-10-09T12:00:00Z', kind: 'agreement', text: 'Ada Test signed the project agreement', actor: null, details: [] }], more: false }],
      '/api/chapters/umd/projects/rise-dc/candidates': [200, [{ personId: 'p3', name: 'Alan Alumnus', status: 'alumni', chapters: [] }]],
      '/api/chapters/umd/sync/run': [202, {}],
    })
    render(<App />)
    await userEvent.click(await screen.findByRole('button', { name: 'New project' }))
    await userEvent.type(screen.getByRole('textbox', { name: /^Name/ }), 'RISE DC')
    expect(screen.getByText(/umd-rise-dc\. Can't be changed later/)).toBeInTheDocument() // the short name it derives
    await userEvent.click(screen.getByRole('button', { name: 'Create draft' }))

    expect(await screen.findByRole('heading', { name: 'RISE DC' })).toBeInTheDocument()
    expect(screen.getByText(/Nobody gets access from a draft/)).toBeInTheDocument()
    // One box per tool, each with its own Add; each resource with who gets it, the agreement, and how the sync stands.
    const github = within(screen.getByRole('region', { name: /GitHub teams/ })).getByRole('group', { name: /^GitHub/ })
    expect(within(github).getByText(/Whole team · Write/)).toBeInTheDocument()
    expect(within(github).getByText('Needs agreement')).toBeInTheDocument()
    expect(within(github).getByText(/To be created|Created when the project starts/)).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: /Slack channels/ })).getByText(/already has something called umd-rise-dc/)).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: /Notion page/ })).getByText(/Hack4Impact-UMD \/ Long Term Success/)).toBeInTheDocument()
    expect(screen.getByText(/The project changed since then/)).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Team' })).getByText('Ada Lovelace')).toBeInTheDocument() // the team, in the sidebar
    expect(screen.getByLabelText('Project agreement')).toHaveTextContent('0 of 1 signed')
    await userEvent.click(screen.getByRole('button', { name: 'Manage team' })) // changes happen in a dialog
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Agreement not sent yet')).toBeInTheDocument()

    await screen.findByRole('option', { name: /Alan Alumnus/ }) // the people who can join load on their own
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Person to add' }), 'p3')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Add' }))
    const calls = () => vi.mocked(fetch).mock.calls.map(([r]) => r as Request)
    const add = calls().find((r) => r.method === 'POST' && r.url.endsWith('/members'))!
    expect(await add.clone().json()).toEqual({ personId: 'p3', roleId: null })

    await userEvent.click(screen.getByRole('button', { name: 'Run dry run' }))
    await waitFor(() => expect(calls().some((r) => r.method === 'POST' && r.url.endsWith('/sync/run'))).toBe(true))
    expect(screen.getByRole('button', { name: 'Running…' })).toBeDisabled()

    // The activity timeline: a big sync opens to show each person; older activity loads on request.
    const feed = screen.getByRole('list', { name: 'Activity' })
    expect(within(feed).getByText(/Lena Lead/)).toBeInTheDocument()
    expect(within(feed).queryByText('Cy Test added to #umd-rise-dc')).not.toBeInTheDocument()
    await userEvent.click(within(feed).getByRole('button', { name: 'Show all 5' }))
    expect(within(feed).getByText('Cy Test added to #umd-rise-dc')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Show all activity' }))
    expect(await within(feed).findByText('Ada Test signed the project agreement')).toBeInTheDocument()
    const older = calls().find((r) => r.url.includes('/activity'))!
    expect(new URL(older.url).searchParams.get('before')).toBe('2026-10-10T12:00:00Z') // older than the last one shown
    expect(screen.queryByRole('button', { name: 'Show older' })).not.toBeInTheDocument() // nothing older left
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

  it('plans in a draft: project requests go to the draft, which shows its preview and conflicts', async () => {
    sessionStorage.setItem('h4i-draft:umd', 'd1')
    window.history.pushState({}, '', '/chapters/umd/projects')
    const at = '2026-10-12T12:00:00Z'
    const draft = { id: 'd1', name: 'Fall 2026 teams', status: 'draft', createdBy: 'Lena Lead', createdAt: at, changes: 2, scheduledFor: null, releasedAt: null, releasedBy: null, releaseError: null }
    const change = (id: string, position: number, summary: string) => ({ id, position, operation: 'add_member', summary, projectId: 'p1', createdBy: 'Lena Lead', createdAt: at })
    api({
      '/api/me': [200, me([umd])],
      '/api/chapters/umd': [200, { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 0, liveProjects: 0, leads: 1 }, registrationLink: 'x', notionProblems: 0 }],
      '/api/chapters/umd/members': [200, []],
      '/api/chapters/umd/sync': [200, { tools: [], changes: [] }],
      '/api/chapters/umd/projects': [200, [{ id: 'p1', name: 'RISE DC', slug: 'rise-dc', status: 'active', type: null, tags: [], term: null, partner: null, members: 2, resources: 4, updatedAt: at, pendingChanges: 2 }]],
      '/api/chapters/umd/drafts/d1': [200, {
        draft, canEdit: true,
        changes: [change('c1', 1, 'Add Ada Test to RISE DC'), change('c2', 2, 'Add Bo Test to RISE DC')],
        conflicts: [{ changeId: 'c2', position: 2, summary: 'Add Bo Test to RISE DC', problem: 'That person isn’t a member of this chapter' }],
        preview: {
          adds: 2, removes: 0, accessChanges: 0, people: 1, resources: 2, afterAgreement: 2,
          perPerson: [{ personId: 'a', name: 'Ada Test', joins: ['RISE DC'], leaves: [], gains: ['umd-rise-dc (Slack)', 'umd-rise-dc@hack4impact.org (Google group)'], loses: [], afterAgreement: ['umd-rise-dc (GitHub)', 'umd-rise-dc (Vaultwarden)'] }],
        },
      }],
    })
    render(<App />)
    expect(await screen.findByText(/Planning in the draft/)).toBeInTheDocument()
    expect(await screen.findByText('2 changes in the draft')).toBeInTheDocument()
    const list = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => new URL(r.url).pathname === '/api/chapters/umd/projects')!
    expect(new URL(list.url).searchParams.get('draft')).toBe('d1') // the projects as the draft has them

    await userEvent.click(screen.getByRole('link', { name: 'Review and release' }))
    expect(await screen.findByText('Adds 1 person to 2 resources · 2 more after the project agreement')).toBeInTheDocument()
    const people = screen.getByRole('list', { name: 'People' })
    expect(within(people).getByTitle('GitHub: umd-rise-dc')).toBeInTheDocument() // a chip with the tool's icon
    expect(within(people).getByText('After agreement')).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Changes' })).getByText('No longer works')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Release now' })).toBeDisabled() // fix the conflict first
    sessionStorage.clear()
  })

  it('lets national change a tool only after confirming, act on the queue, and retry dead letters', async () => {
    window.history.pushState({}, '', '/national')
    const tool = (name: string, over: object = {}) => ({ tool: name, enabled: true, dryRun: true, writable: true, pauseReason: null, maxRemovals: 25, lastRun: null, ...over })
    api({
      '/api/me': [200, { ...me([umd]), nationalAdmin: true }],
      'GET /api/national/tools': [200, [tool('slack'), tool('documenso', { writable: false })]],
      'PUT /api/national/tools/slack': [200, [tool('slack', { dryRun: false }), tool('documenso', { writable: false })]],
      'GET /api/national/queue': [200, [{ id: 't1', tool: 'notion', action: 'add_member', description: 'Add Ada Lovelace (ada@hack4impact.org) to the Notion teamspace UMD', createdAt: '2026-10-10T12:00:00Z', status: 'open', doneBy: null, doneAt: null }]],
      'POST /api/national/queue/t1': [200, []],
      'GET /api/national/dead-letters': [200, [{ personId: 'p1', person: 'Ada Lovelace', resourceId: 'r1', tool: 'github', resource: 'umd-rise-dc', attempts: 5, lastError: 'boom', lastAttemptAt: null }]],
      'POST /api/national/dead-letters/retry': [200, []],
    })
    render(<App />)
    const tools = await screen.findByRole('list', { name: 'Tools' })
    expect(within(tools).getAllByText('Dry run')).toHaveLength(2) // state in words
    expect(within(tools).getByText(/read-only here/)).toBeInTheDocument()

    await userEvent.click(within(tools).getAllByRole('button', { name: 'Leave dry run' })[0]!)
    const calls = () => vi.mocked(fetch).mock.calls.map(([r]) => r as Request)
    expect(calls().some((r) => r.method === 'PUT')).toBe(false) // nothing yet: it asks first
    await userEvent.click(within(tools).getByRole('button', { name: 'Yes, let the portal change Slack' }))
    const put = calls().find((r) => r.method === 'PUT')!
    expect(await put.clone().json()).toEqual({ enabled: true, dryRun: false, reason: null })
    expect(await within(tools).findByText('Changing the tool')).toBeInTheDocument()

    await userEvent.click(within(screen.getByRole('list', { name: 'Open tasks' })).getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(calls().some((r) => r.method === 'POST' && r.url.endsWith('/queue/t1'))).toBe(true))
    await userEvent.click(within(screen.getByRole('list', { name: 'Dead letters' })).getByRole('button', { name: 'Retry' }))
    const retry = calls().find((r) => r.url.endsWith('/dead-letters/retry'))!
    expect(await retry.clone().json()).toEqual([{ personId: 'p1', resourceId: 'r1' }])
  })

  const applicant = (over: object = {}) => ({
    kind: 'new', status: 'started', schoolEmail: 'ada@umd.edu', emailVerified: true, githubLogin: null, githubConfigured: true,
    chapter: { code: 'umd', name: 'Hack4Impact UMD', emailDomains: ['umd.edu'], applicationsOpen: true, applicationUrl: null, questions: [{ id: 'q1', label: 'Why join?', kind: 'long_text', options: [], required: true }] },
    details: { firstName: null, lastName: null, preferredName: null, personalEmail: null, graduationYear: null, roleInterest: null, stillActive: null },
    answers: {}, missing: ['your GitHub account', 'your name'], declineReason: null, account: null, agreements: [], ...over,
  })

  it('takes a new applicant from the chapter link through a code to a sent application', async () => {
    window.history.pushState({}, '', '/join/umd')
    api({
      '/api/apply/chapters/umd': [200, applicant().chapter],
      '/api/apply/chapters/umd/code': [202, {}],
      '/api/apply/chapters/umd/verify': [200, applicant()],
      'PUT /api/apply/me': [200, applicant({ githubLogin: 'ada-gh', missing: [] })],
      '/api/apply/me/submit': [200, applicant({ status: 'submitted' })],
    })
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Hack4Impact UMD' })).toBeInTheDocument()
    await userEvent.type(screen.getByRole('textbox', { name: /School email/ }), 'ada@umd.edu')
    await userEvent.click(screen.getByRole('button', { name: 'Send code' }))
    await userEvent.type(await screen.findByRole('textbox', { name: 'Code' }), '123456')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    expect(await screen.findByText(/Still needed: your GitHub account, your name/)).toBeInTheDocument() // the checklist, in words
    expect(screen.getByRole('button', { name: 'Sign in with GitHub' })).toBeInTheDocument()
    await userEvent.type(screen.getByRole('textbox', { name: /First name/ }), 'Ada')
    await userEvent.type(screen.getByRole('textbox', { name: /Why join\?/ }), 'To build')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    const put = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => r.method === 'PUT')!
    expect(await put.clone().json()).toMatchObject({ details: { firstName: 'Ada' }, answers: { q1: 'To build' } })
    await userEvent.click(await screen.findByRole('button', { name: 'Send application' }))
    expect(await screen.findByRole('heading', { name: 'Application sent' })).toBeInTheDocument()
  })

  it('sends applicants to the chapter’s own application when it has turned the portal’s off', async () => {
    window.history.pushState({}, '', '/join/umd')
    api({ '/api/apply/chapters/umd': [200, { ...applicant().chapter, applicationsOpen: false, applicationUrl: 'https://forms.gle/umd' }] })
    render(<App />)
    expect(await screen.findByText(/runs its own application process/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Apply to Hack4Impact UMD' })).toHaveAttribute('href', 'https://forms.gle/umd')
    expect(screen.queryByRole('textbox', { name: /School email/ })).not.toBeInTheDocument()
  })

  it('has applicants sign the volunteer agreement in Documenso before they can send', async () => {
    window.history.pushState({}, '', '/apply')
    const view = applicant({
      githubLogin: 'ada-gh', missing: ['the Volunteer agreement'],
      agreements: [{ kind: 'volunteer', name: 'Volunteer agreement', status: 'not_started', signUrl: null }, { kind: 'code_of_conduct', name: 'Code of conduct', status: 'signed', signUrl: null }],
    })
    api({
      '/api/apply/me': [200, view],
      'PUT /api/apply/me': [200, view],
      '/api/apply/me/agreements/volunteer': [200, { url: 'https://sign.example/sign/tok' }],
    })
    render(<App />)
    expect(await screen.findByRole('button', { name: 'Send application' })).toBeDisabled() // not signed yet
    expect(screen.getByText('Signed')).toBeInTheDocument() // the code of conduct
    await userEvent.click(screen.getByRole('button', { name: 'Read and sign' }))
    const calls = () => vi.mocked(fetch).mock.calls.map(([r]) => r as Request)
    await waitFor(() => expect(calls().some((r) => r.url.endsWith('/api/apply/me/agreements/volunteer'))).toBe(true))
    expect(calls().findIndex((r) => r.method === 'PUT')).toBeLessThan(calls().findIndex((r) => r.url.endsWith('/agreements/volunteer'))) // saved first: the name goes on it
  })

  it('lets national set up an agreement template from Documenso', async () => {
    window.history.pushState({}, '', '/national')
    const templates = (list: unknown[]) => ({ templates: list, connected: true, sending: false })
    api({
      '/api/me': [200, { ...me([umd]), nationalAdmin: true }],
      '/api/national/tools': [200, []], '/api/national/queue': [200, []], '/api/national/dead-letters': [200, []],
      '/api/national/claims/preview': [200, { eligible: 0, alreadySent: 0, withoutEmail: 0 }],
      'GET /api/national/agreement-templates': [200, templates([])],
      'POST /api/national/agreement-templates': [200, templates([{ id: 't1', kind: 'project', name: 'Project agreement', documensoTemplateId: 'envelope_1', version: 1, active: true, createdAt: '2026-10-11T12:00:00Z', sent: 0 }])],
    })
    render(<App />)
    const list = await screen.findByRole('list', { name: 'Agreement templates' })
    expect(within(list).getAllByText('Not set up')).toHaveLength(3)
    expect(screen.getByText('Dry run: nothing is sent')).toBeInTheDocument()
    await userEvent.click(within(list).getAllByRole('button', { name: 'Set up' })[2]!) // the project agreement
    await userEvent.type(within(list).getByRole('textbox', { name: 'Documenso template' }), 'envelope_1')
    await userEvent.click(within(list).getByRole('button', { name: 'Save' }))
    const post = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => r.method === 'POST')!
    expect(await post.clone().json()).toEqual({ kind: 'project', name: 'Project agreement', documensoTemplateId: 'envelope_1' })
    expect(await within(list).findByText('Project agreement · version 1')).toBeInTheDocument()
  })

  it('lets an imported member claim their record and say they have moved on', async () => {
    window.history.pushState({}, '', '/claim/tok123')
    const claim = applicant({ kind: 'claim', githubLogin: 'ada-gh', details: { ...applicant().details, firstName: 'Ada', lastName: 'Lovelace' }, missing: ['whether you’re still in the chapter'] })
    api({
      '/api/apply/claim': [200, claim],
      'PUT /api/apply/me': [200, { ...claim, missing: [] }],
      '/api/apply/me/submit': [200, { ...claim, status: 'completed', account: 'ada@umd.hack4impact.org' }],
    })
    render(<App />)
    expect(await screen.findByText('Claim your record')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /First name/ })).toHaveValue('Ada') // pre-filled from the import
    await userEvent.click(screen.getByRole('radio', { name: /I’ve moved on/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Claim my record' }))
    expect(await screen.findByRole('heading', { name: 'Record claimed' })).toBeInTheDocument()
    expect(screen.getByText('ada@umd.hack4impact.org')).toBeInTheDocument()
    const put = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => r.method === 'PUT')!
    expect((await put.clone().json()).details.stillActive).toBe(false)
  })

  it('hides the questions and the empty waiting list when the chapter runs its own application process', async () => {
    const chapter = { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 1, liveProjects: 0, leads: 1 }, registrationLink: 'https://join.hack4impact.org/umd', notionProblems: 0 }
    const settings = (open: boolean) => ({
      questions: [{ id: 'q1', label: 'Why join?', kind: 'long_text', options: [], required: true }], googleDomain: 'umd.hack4impact.org', googleOrgUnit: '/UMD',
      applicationsOpen: open, applicationUrl: open ? null : 'https://forms.gle/umd', canEditQuestions: true, canEditAccounts: false,
    })
    const routes = {
      '/api/me': [200, me([umd])], '/api/chapters/umd': [200, chapter], '/api/chapters/umd/members': [200, []], '/api/chapters/umd/sync': [200, { tools: [], changes: [] }],
      '/api/chapters/umd/registrations': [200, []], '/api/chapters/umd/roster': [200, []],
      '/api/chapters/umd/notion': [200, { teamspaceId: null, defaultParent: null, template: null, titlePattern: '{project} ({semester})', routes: [], configured: false, problems: 0, canEdit: true }],
    } satisfies Record<string, [number, unknown]>

    window.history.pushState({}, '', '/chapters/umd/settings')
    api({ ...routes, '/api/chapters/umd/registration-settings': [200, settings(true)] })
    const { unmount } = render(<App />)
    expect(await screen.findByRole('button', { name: 'Add question' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: /our own application process/ }))
    expect(screen.queryByRole('button', { name: 'Add question' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Question' })).not.toBeInTheDocument()
    unmount()

    window.history.pushState({}, '', '/chapters/umd/approvals')
    api({ ...routes, '/api/chapters/umd/registration-settings': [200, settings(false)] })
    render(<App />)
    expect(await screen.findByText('Invite a roster')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Waiting for a decision')).not.toBeInTheDocument())
  })

  it('shows leads the match and lets them approve as a matched record or decline with a reason', async () => {
    window.history.pushState({}, '', '/chapters/umd/approvals')
    const reg = {
      id: 'r1', kind: 'new', status: 'submitted', name: 'Ada Lovelace', schoolEmail: 'ada@umd.edu', githubLogin: 'ada-gh', graduationYear: 2028, roleInterest: 'Developer',
      matchStatus: 'duplicate', matches: [{ personId: 'p9', name: 'Ada Lovelace', status: 'alumni', chapters: ['Hack4Impact UMD'], why: ['same email'] }, { personId: 'p8', name: 'Ada L', status: 'active', chapters: [], why: ['same name in this chapter'] }],
      answers: { 'Why join?': 'To build' }, githubChanged: false, submittedAt: '2026-10-11T12:00:00Z', decidedAt: null, declineReason: null,
    }
    api({
      '/api/me': [200, me([umd])],
      '/api/chapters/umd': [200, { id: 'c1', code: 'umd', name: 'Hack4Impact UMD', status: 'active', role: 'lead', stats: { activeMembers: 2, alumni: 1, liveProjects: 0, leads: 1 }, registrationLink: 'https://join.hack4impact.org/umd', notionProblems: 0 }],
      '/api/chapters/umd/members': [200, []],
      '/api/chapters/umd/sync': [200, { tools: [], changes: [] }],
      '/api/chapters/umd/registrations': [200, [reg]],
      '/api/chapters/umd/registrations/r1/approve': [200, []],
      '/api/chapters/umd/roster': [200, []],
    })
    render(<App />)
    const list = await screen.findByRole('list', { name: 'Waiting for a decision' })
    expect(within(list).getByText('Possible duplicate')).toBeInTheDocument()
    expect(within(list).getByText(/same email/)).toBeInTheDocument()
    expect(within(list).getByText('To build')).toBeInTheDocument()

    await userEvent.click(within(list).getByRole('button', { name: 'Decline' }))
    expect(within(list).getAllByRole('button', { name: 'Decline' }).at(-1)).toBeDisabled() // a reason first
    await userEvent.click(within(list).getByRole('button', { name: 'Cancel' }))

    await userEvent.click(within(list).getAllByRole('radio')[1]!) // the alumni record
    await userEvent.click(within(list).getByRole('button', { name: 'Approve as this record' }))
    const approve = vi.mocked(fetch).mock.calls.map(([r]) => r as Request).find((r) => r.url.endsWith('/approve'))!
    expect(await approve.clone().json()).toEqual({ personId: 'p9' })
  })
})
