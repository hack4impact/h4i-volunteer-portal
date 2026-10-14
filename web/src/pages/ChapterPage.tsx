import { GearIcon, GitPullRequestDraftIcon, HomeIcon, InboxIcon, LinkIcon, PeopleIcon, ProjectIcon, ZapIcon } from '@primer/octicons-react'
import { Banner, CounterLabel, Spinner, UnderlineNav } from '@primer/react'
import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { api, type ChapterOverview, type ChapterSync, type Me, type MemberRow } from '../api/client'
import { ROLE_LABEL } from '../components/Status'
import { TopBar } from '../components/TopBar'
import { Adoption } from './Adoption'
import { useActiveDraft } from '../api/drafts'
import { Approvals } from './Approvals'
import { Automations } from './Automations'
import { DraftBanner, Drafts } from './Drafts'
import { MembersTable } from './MembersTable'
import { Overview } from './Overview'
import { ProjectPage } from './ProjectPage'
import { Projects } from './Projects'
import { Settings } from './Settings'

export type ChapterTab = 'overview' | 'members' | 'approvals' | 'projects' | 'automations' | 'drafts' | 'adoption' | 'settings'

/** One chapter: page header, tabs, and the tab's content. A project's page sits under the Projects tab. */
export function ChapterPage({ me, tab }: { me: Me; tab: ChapterTab }) {
  const { code = '', slug, draftId } = useParams()
  const [draft] = useActiveDraft(code)
  const { pathname } = useLocation()
  const [chapter, setChapter] = useState<ChapterOverview | null>(null)
  const [members, setMembers] = useState<MemberRow[] | null>(null)
  const [sync, setSync] = useState<ChapterSync | null>(null)
  const [waiting, setWaiting] = useState<number | undefined>(undefined)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setChapter(null)
    setMembers(null)
    setSync(null)
    setError(null)
    const params = { params: { path: { code } } }
    void api.GET('/api/chapters/{code}', params).then(({ data, response }) =>
      data ? setChapter(data) : setError(response.status === 403 ? 'You have no role in this chapter.' : 'This chapter could not be loaded.'),
    )
    void api.GET('/api/chapters/{code}/members', params).then(({ data }) => data && setMembers(data))
    void api.GET('/api/chapters/{code}/sync', params).then(({ data }) => data && setSync(data))
    void api.GET('/api/chapters/{code}/registrations', params).then(({ data }) => setWaiting(data?.filter((r) => r.status === 'submitted').length))
  }, [code])

  // National's own chapter (decision 141) has projects but no members, applications or chapter rules of its own.
  const national = chapter?.kind === 'national'
  const tabs = [
    { key: 'overview', label: 'Overview', to: `/chapters/${code}`, icon: HomeIcon },
    ...(national ? [] : [
      { key: 'members', label: 'Members', to: `/chapters/${code}/members`, count: members?.length, icon: PeopleIcon },
      { key: 'approvals', label: 'Approvals', to: `/chapters/${code}/approvals`, count: waiting || undefined, icon: InboxIcon },
    ]),
    { key: 'projects', label: 'Projects', to: `/chapters/${code}/projects`, icon: ProjectIcon },
    ...(national ? [] : [{ key: 'automations', label: 'Automations', to: `/chapters/${code}/automations`, icon: ZapIcon }]),
    ...(chapter && ['lead', 'co_lead', 'national'].includes(chapter.role) ? [{ key: 'drafts', label: 'Drafts', to: `/chapters/${code}/drafts`, icon: GitPullRequestDraftIcon }] : []),
    ...(national ? [] : [{ key: 'adoption', label: 'Adoption', to: `/chapters/${code}/adoption`, icon: LinkIcon }]),
    { key: 'settings', label: 'Settings', to: `/chapters/${code}/settings`, icon: GearIcon },
  ]

  return (
    <>
      <TopBar me={me} chapterCode={code} />
      {error ? (
        <div className="center">
          <Banner title="Not available" description={error} variant="critical" />
        </div>
      ) : !chapter ? (
        <div className="center">
          <Spinner aria-label="Loading chapter" />
        </div>
      ) : (
        <>
          <div className="page-head">
            <div className="inner">
              <div className="eyebrow">{national ? 'National projects' : <>Chapter · {ROLE_LABEL[chapter.role] ?? chapter.role}</>}</div>
              <h1 className="page-title">{chapter.name}</h1>
              <UnderlineNav aria-label="Chapter sections">
                {tabs.map((t) => (
                  <UnderlineNav.Item key={t.key} as={Link} to={t.to} icon={t.icon} aria-current={pathname === t.to || t.key === tab ? 'page' : undefined}>
                    {t.label}
                    {t.count !== undefined && <CounterLabel>{t.count}</CounterLabel>}
                  </UnderlineNav.Item>
                ))}
              </UnderlineNav>
            </div>
          </div>
          {(tab === 'projects' || tab === 'automations') && <DraftBanner code={chapter.code} />}
          {tab === 'overview' ? (
            <Overview chapter={chapter} members={members} sync={sync} />
          ) : tab === 'members' ? (
            <MembersTable code={chapter.code} members={members} national={me.nationalAdmin} />
          ) : tab === 'projects' && slug ? (
            <ProjectPage key={draft ?? 'live'} code={chapter.code} slug={slug} />
          ) : tab === 'projects' ? (
            <Projects key={draft ?? 'live'} code={chapter.code} canEdit={['lead', 'co_lead', 'national'].includes(chapter.role)} />
          ) : tab === 'automations' ? (
            <Automations scope={{ kind: 'chapter', code: chapter.code }} />
          ) : tab === 'drafts' ? (
            <Drafts code={chapter.code} id={draftId} />
          ) : tab === 'approvals' ? (
            <Approvals code={chapter.code} canEdit={['lead', 'co_lead', 'national'].includes(chapter.role)} />
          ) : tab === 'settings' ? (
            <Settings code={chapter.code} national={national} />
          ) : (
            <Adoption code={chapter.code} />
          )}
        </>
      )}
    </>
  )
}
