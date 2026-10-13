import { Banner, Button, Spinner } from '@primer/react'
import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { api, reason, type Me, type MemberHome as Home } from '../api/client'
import { PROJECT_STATUS, ROLE_LABEL, STATUS_LABEL, Status, TOOL_NAME, resourceName } from '../components/Status'
import { ToolIcon } from '../components/ToolIcon'
import { Mascot } from '../components/Mascot'
import { TopBar } from '../components/TopBar'

const STATE: Record<string, [string, 'ok' | 'warn' | 'off']> = {
  ready: ['Ready', 'ok'],
  invited: ['Invited: accept it in your email', 'warn'],
  setting_up: ['Being set up', 'off'],
  after_agreement: ['After you sign the agreement', 'off'],
}

/** Where a project opens: its chapter page for the chapter's roles, else the project lead's own page. */
export const projectLink = (me: Me, code: string, slug: string) =>
  me.chapters.some((c) => c.code === code) ? `/chapters/${code}/projects/${slug}` : `/projects/${code}/${slug}`

/**
 * A member's own page (PRD member role: their projects, links and agreements, and why they have each resource).
 * Everyone signed in who is a member gets it; it's the home page of members without a chapter role.
 */
export function MemberHome({ me }: { me: Me }) {
  const [home, setHome] = useState<Home | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    void api.GET('/api/me/home').then(({ data, error: failed, response }) => (data ? setHome(data) : setError(reason(failed, response, 'Your page could not be loaded'))))
  }, [])

  async function sign(id: string) {
    const { data, error: failed, response } = await api.POST('/api/me/agreements/{id}/sign', { params: { path: { id } } })
    if (!data) return setError(reason(failed, response, 'The agreement couldn’t be opened'))
    window.location.assign(data.url)
  }

  return (
    <>
      <TopBar me={me} />
      {error ? <div className="center"><Banner title="Not available" description={error} variant="critical" /></div>
        : !home ? <div className="center"><Spinner aria-label="Loading your page" /></div>
        : (
          <>
            <div className="page-head">
              <div className="inner">
                <div className="eyebrow">{home.status === 'active' ? 'Member' : STATUS_LABEL[home.status] ?? home.status}</div>
                <h1 className="page-title">{home.name}</h1>
                <div className="member-chapters">
                  {home.chapters.map((c) => (
                    <span key={c.code}>{c.name}{c.role ? ` · ${ROLE_LABEL[c.role] ?? c.role}` : ''}</span>
                  ))}
                </div>
              </div>
            </div>
            <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
              {home.agreements.some((a) => a.status === 'sent') && (
                <Banner title="An agreement is waiting for your signature" variant="warning"
                  description="Some of your project’s repositories and vault collections open once you sign the project agreement." />
              )}

              <section className="box" aria-labelledby="my-projects">
                <div className="box-head" id="my-projects">Your projects <span className="Counter">{home.projects.length}</span></div>
                {home.projects.length === 0 && <div className="box-row muted mascot-row"><Mascot pose="peek" size={64} />You’re not on a project right now.</div>}
                {home.projects.map((p) => {
                  const [label, tone] = PROJECT_STATUS[p.status] ?? [p.status, 'off']
                  return (
                    <div className="box-row member-row" key={`${p.chapterCode}/${p.slug}`}>
                      {p.canOpen ? <Link to={projectLink(me, p.chapterCode, p.slug)}><strong>{p.name}</strong></Link> : <strong>{p.name}</strong>}
                      <Status tone={tone}>{label}</Status>
                      <span className="muted">{p.role ?? 'No role'}{p.leads ? ' · you lead it' : ''} · {p.chapter}</span>
                      <span style={{ marginLeft: 'auto' }}>
                        {p.agreementStatus === 'pending' ? <Status tone="off">Agreement not signed</Status> : <Status tone="ok">{p.agreementStatus === 'waived' ? 'Agreement waived' : 'Agreement signed'}</Status>}
                      </span>
                    </div>
                  )
                })}
              </section>

              <section className="box" aria-labelledby="my-access">
                <div className="box-head" id="my-access">Your access <span className="Counter">{home.access.length}</span></div>
                {home.access.length === 0 && <div className="box-row muted">Nothing yet. Access follows once you’re on a project or your chapter adds you.</div>}
                <ul className="adopt-list" aria-label="Your access">
                  {home.access.map((a) => {
                    const [label, tone] = STATE[a.state] ?? [a.state, 'off']
                    return (
                      <li key={`${a.tool}:${a.name}`}>
                        <div className="access-row">
                          <span className="access-icon"><ToolIcon tool={a.tool} label /></span>
                          <div>
                            <div className="access-line">
                              {a.url ? <a className="res-name" href={a.url} target="_blank" rel="noreferrer">{resourceName(a.tool, a.name)}</a> : <span className="res-name">{resourceName(a.tool, a.name)}</span>}
                              <span className="muted">{TOOL_NAME[a.tool] ?? a.tool}</span>
                              <span className="access-state"><Status tone={tone}>{label}</Status></span>
                            </div>
                            <div className="muted list-sub">Why: {a.reasons.join(' · ')}</div>
                          </div>
                        </div>
                      </li>
                    )
                  })}
                </ul>
              </section>

              <section className="box" aria-labelledby="my-agreements">
                <div className="box-head" id="my-agreements">Your agreements</div>
                {home.agreements.length === 0 && <div className="box-row muted">No agreements yet.</div>}
                {home.agreements.map((a) => (
                  <div className="box-row member-row" key={a.id}>
                    <strong>{a.name}</strong>
                    {a.project && <span className="muted">{a.project}</span>}
                    <span style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
                      {a.status === 'signed' && <Status tone="ok">Signed {a.signedAt ? new Date(a.signedAt).toLocaleDateString() : ''}</Status>}
                      {a.status === 'signed' && a.hasPdf && <a href={`/api/agreements/${a.id}/pdf`}>PDF</a>}
                      {a.status === 'declined' && <Status tone="danger">Declined</Status>}
                      {a.status === 'sent' && (a.canSign
                        ? <Button size="small" variant="primary" onClick={() => void sign(a.id)}>Sign it</Button>
                        : <Status tone="warn">Waiting for you: the link is in your email</Status>)}
                    </span>
                  </div>
                ))}
              </section>
            </div>
          </>
        )}
    </>
  )
}
