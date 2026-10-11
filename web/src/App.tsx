import { Banner, BaseStyles, Button, Spinner, ThemeProvider } from '@primer/react'
import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { api, type Me, signIn } from './api/client'
import { TopBar } from './components/TopBar'
import { ChapterPage } from './pages/ChapterPage'
import { National } from './pages/National'

type Session = { state: 'loading' } | { state: 'signed-out' } | { state: 'signed-in'; me: Me }

/** Loads who's signed in, then routes: sign-in page, no-access page, or the first chapter they can see. */
export function App() {
  const [session, setSession] = useState<Session>({ state: 'loading' })
  useEffect(() => {
    void api.GET('/api/me').then(({ data }) => setSession(data ? { state: 'signed-in', me: data } : { state: 'signed-out' }))
  }, [])

  return (
    <ThemeProvider colorMode="auto">
      <BaseStyles className="app">
        {session.state === 'loading' ? (
          <div className="center"><Spinner aria-label="Loading" /></div>
        ) : session.state === 'signed-out' ? (
          <SignIn denied={new URLSearchParams(window.location.search).get('signin') === 'denied'} />
        ) : (
          <BrowserRouter>
            <Routes>
              <Route path="/chapters/:code" element={<ChapterPage me={session.me} tab="overview" />} />
              <Route path="/chapters/:code/members" element={<ChapterPage me={session.me} tab="members" />} />
              <Route path="/chapters/:code/projects" element={<ChapterPage me={session.me} tab="projects" />} />
              <Route path="/chapters/:code/projects/:slug" element={<ChapterPage me={session.me} tab="projects" />} />
              <Route path="/chapters/:code/adoption" element={<ChapterPage me={session.me} tab="adoption" />} />
              <Route path="/chapters/:code/settings" element={<ChapterPage me={session.me} tab="settings" />} />
              <Route path="/national" element={<National me={session.me} />} />
              <Route
                path="*"
                element={session.me.chapters.length > 0 ? <Navigate to={`/chapters/${session.me.chapters[0]!.code}`} replace /> : <NoAccess me={session.me} />}
              />
            </Routes>
          </BrowserRouter>
        )}
      </BaseStyles>
    </ThemeProvider>
  )
}

function SignIn({ denied }: { denied: boolean }) {
  return (
    <div className="center">
      <h1 className="page-title">H4I Portal</h1>
      {denied && (
        <div style={{ marginBottom: 16, textAlign: 'left' }}>
          <Banner title="That account can't sign in" description="Use your Hack4Impact Google account: an @hack4impact.org address or a chapter one like @umd.hack4impact.org." variant="critical" />
        </div>
      )}
      <p className="muted">Chapter leads and national sign in with their Hack4Impact Google account.</p>
      <Button variant="primary" size="large" onClick={signIn}>Sign in with Google</Button>
    </div>
  )
}

function NoAccess({ me }: { me: Me }) {
  return (
    <>
      <TopBar me={me} />
      <div className="center">
        <h1 className="page-title">No chapter access yet</h1>
        <p>
          You're signed in as <strong>{me.email}</strong>, but you don't have a lead or viewer role in any chapter.
        </p>
        <p className="muted">Ask your chapter lead or the national team to add you.</p>
      </div>
    </>
  )
}
