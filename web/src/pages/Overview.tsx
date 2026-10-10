import { Link as PrimerLink } from '@primer/react'
import { Link } from 'react-router'
import { type ChapterOverview, type MemberRow } from '../api/client'
import { ROLE_LABEL, Status } from '../components/Status'

const TOOLS = ['google', 'github', 'slack', 'notion', 'vaultwarden']

/** Chapter overview: leads, tool coverage, and the side pane with counts and links. */
export function Overview({ chapter, members }: { chapter: ChapterOverview; members: MemberRow[] | null }) {
  const leads = (members ?? []).filter((m) => m.chapterRole)
  const active = (members ?? []).filter((m) => m.status === 'active' || m.status === 'removal_requested')
  return (
    <div className="page-body">
      <main style={{ display: 'grid', gap: 24, alignContent: 'start' }}>
        <section className="box" aria-labelledby="leads">
          <div className="box-head" id="leads">Leads and viewers</div>
          {leads.length === 0 ? (
            <div className="box-row muted">No chapter roles yet. National grants the first leads.</div>
          ) : (
            leads.map((m) => (
              <div className="box-row" key={m.personId}>
                <strong>{m.name}</strong>{' '}
                <span className="muted">
                  {ROLE_LABEL[m.chapterRole!]}
                  {m.title ? ` · ${m.title}` : ''}
                </span>
              </div>
            ))
          )}
        </section>
        <section className="box" aria-labelledby="coverage">
          <div className="box-head" id="coverage">Tool accounts of active members</div>
          {TOOLS.map((tool) => {
            const linked = active.filter((m) => m.accounts.some((a) => a.tool === tool)).length
            return (
              <div className="box-row" key={tool} style={{ display: 'flex', justifyContent: 'space-between' }}>
                <span style={{ textTransform: 'capitalize' }}>{tool}</span>
                <Status tone={linked === active.length && active.length > 0 ? 'ok' : linked > 0 ? 'warn' : 'off'}>
                  {linked} of {active.length} linked
                </Status>
              </div>
            )
          })}
          <div className="box-row muted">Sync isn't running yet (build plan step 6). These are the accounts the portal knows about.</div>
        </section>
      </main>
      <aside>
        <div className="pane-section" style={{ paddingTop: 0 }}>
          <div className="pane-label">This chapter</div>
          <div className="stats">
            <Stat label="Active members" value={chapter.stats.activeMembers} />
            <Stat label="Alumni" value={chapter.stats.alumni} />
            <Stat label="Live projects" value={chapter.stats.liveProjects} />
            <Stat label="Leads" value={chapter.stats.leads} />
          </div>
        </div>
        <div className="pane-section">
          <div className="pane-label">Chapter links</div>
          <div>
            <PrimerLink href={chapter.registrationLink}>Registration link</PrimerLink>{' '}
            <span className="muted">{chapter.registrationLink.replace(/^https?:\/\//, '')}</span>
          </div>
          <div>
            <PrimerLink as={Link} to={`/chapters/${chapter.code}/members`}>
              Members
            </PrimerLink>
          </div>
        </div>
        <div className="dots" aria-hidden="true" />
      </aside>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  )
}
