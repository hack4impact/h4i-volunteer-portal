import { Link as PrimerLink } from '@primer/react'
import { Link } from 'react-router'
import { type ChapterOverview, type ChapterSync, type MemberRow } from '../api/client'
import { ROLE_LABEL, Status } from '../components/Status'

const TOOLS = ['google', 'github', 'slack', 'notion', 'vaultwarden']

/** Chapter overview: leads, tool coverage, sync dry runs, and the side pane with counts and links. */
export function Overview({ chapter, members, sync }: { chapter: ChapterOverview; members: MemberRow[] | null; sync: ChapterSync | null }) {
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
          <div className="box-row muted">These are the accounts the portal knows about.</div>
        </section>
        <SyncBox sync={sync} />
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

const CHANGE_LABEL: Record<string, string> = {
  add: 'Add',
  change: 'Change access',
  remove: 'Remove',
  drift: 'Not granted by the portal',
  unmatched_account: 'Account not linked to anyone',
  missing_resource: 'Missing in the tool',
}

/** The latest dry run per tool. Nothing is applied yet: these are the changes a real run would make. */
function SyncBox({ sync }: { sync: ChapterSync | null }) {
  const shown = sync?.changes.slice(0, 10) ?? []
  // Counts cover every change; the API sends at most 200 rows.
  const total = (sync?.tools ?? []).reduce((n, t) => n + t.adds + t.changes + t.removals + t.drift + t.unmatchedAccounts + t.missingResources, 0)
  return (
    <section className="box" aria-labelledby="sync">
      <div className="box-head" id="sync">Sync (dry run)</div>
      {!sync || sync.tools.length === 0 ? (
        <div className="box-row muted">No sync has run yet.</div>
      ) : (
        <>
          {sync.tools.map((t) => (
            <div className="box-row" key={t.tool} style={{ display: 'flex', justifyContent: 'space-between', gap: 16 }}>
              <span style={{ textTransform: 'capitalize' }}>{t.tool}</span>
              <span>
                <Status tone={t.status === 'completed' ? 'ok' : t.status === 'paused' ? 'warn' : 'danger'}>
                  {t.status === 'completed' ? 'Dry run' : t.status === 'paused' ? 'Paused' : 'Failed'}
                </Status>{' '}
                <span className="muted">
                  {summary(t)} · {new Date(t.ranAt).toLocaleString()}
                </span>
              </span>
            </div>
          ))}
          {shown.map((c, i) => (
            <div className="box-row" key={i}>
              <strong>{CHANGE_LABEL[c.kind] ?? c.kind}</strong>{' '}
              <span>
                {c.person ?? c.accountId ?? ''} · {c.resource}
              </span>{' '}
              <span className="muted">
                {c.fromAccess && c.toAccess ? `${c.fromAccess} → ${c.toAccess}` : (c.toAccess ?? '')}
              </span>
            </div>
          ))}
          {total > shown.length && (
            <div className="box-row muted">And {total - shown.length} more.</div>
          )}
        </>
      )}
    </section>
  )
}

function summary(t: ChapterSync['tools'][number]) {
  const parts = [
    [t.adds, 'to add'],
    [t.changes, 'to change'],
    [t.removals, 'to remove'],
    [t.drift, 'drift'],
    [t.unmatchedAccounts, 'unlinked'],
    [t.missingResources, 'missing'],
  ].filter(([n]) => (n as number) > 0)
  return parts.length ? parts.map(([n, label]) => `${n} ${label}`).join(', ') : 'in sync'
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value}</div>
    </div>
  )
}
