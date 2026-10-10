import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'

/** Answers API calls from a table of URL path → [status, body]. */
function api(routes: Record<string, [number, unknown]>) {
  vi.stubGlobal('fetch', vi.fn(async (input: Request | string) => {
    const path = new URL(typeof input === 'string' ? input : input.url, 'http://localhost').pathname
    const [status, body] = routes[path] ?? [404, {}]
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
      '/api/chapters/umd/members': [200, [
        { personId: 'p1', name: 'Lena Lead', email: 'lena@hack4impact.org', status: 'active', kind: 'student', chapterRole: 'lead', title: null, projects: [], accounts: [{ tool: 'google', state: 'confirmed' }], claimed: true },
        { personId: 'p2', name: 'Alan Alumnus', email: null, status: 'alumni', kind: 'student', chapterRole: null, title: null, projects: ['RISE DC Portal'], accounts: [], claimed: false },
      ]],
    })
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'Hack4Impact UMD' })).toBeInTheDocument()
    expect(screen.getByText('Registration link')).toHaveAttribute('href', 'https://join.hack4impact.org/umd')

    await userEvent.click(within(screen.getByRole('navigation', { name: 'Chapter sections' })).getByRole('link', { name: /Members/ }))
    const table = await screen.findByRole('table', { name: 'Members' })
    expect(within(table).getByText('Alumni')).toBeInTheDocument() // status in words, not just a colored dot
    expect(within(table).getByText('RISE DC Portal')).toBeInTheDocument()

    await userEvent.type(screen.getByRole('textbox', { name: 'Filter members' }), 'lena')
    await waitFor(() => expect(within(table).queryByText('Alan Alumnus')).not.toBeInTheDocument())
    expect(screen.getByText('1 of 2')).toBeInTheDocument()
  })
})
