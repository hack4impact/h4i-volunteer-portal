import { Banner, CounterLabel, Spinner, UnderlineNav } from '@primer/react'
import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { api, type ChapterOverview, type ChapterSync, type Me, type MemberRow } from '../api/client'
import { ROLE_LABEL } from '../components/Status'
import { TopBar } from '../components/TopBar'
import { Adoption } from './Adoption'
import { MembersTable } from './MembersTable'
import { Overview } from './Overview'
import { ProjectPage } from './ProjectPage'
import { Projects } from './Projects'
import { Settings } from './Settings'

export type ChapterTab = 'overview' | 'members' | 'projects' | 'adoption' | 'settings'

/** One chapter: page header, tabs, and the tab's content. A project's page sits under the Projects tab. */
export function ChapterPage({ me, tab }: { me: Me; tab: ChapterTab }) {
  const { code = '', slug } = useParams()
  const { pathname } = useLocation()
  const [chapter, setChapter] = useState<ChapterOverview | null>(null)
  const [members, setMembers] = useState<MemberRow[] | null>(null)
  const [sync, setSync] = useState<ChapterSync | null>(null)
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
  }, [code])

  const tabs = [
    { key: 'overview', label: 'Overview', to: `/chapters/${code}` },
    { key: 'members', label: 'Members', to: `/chapters/${code}/members`, count: members?.length },
    { key: 'projects', label: 'Projects', to: `/chapters/${code}/projects` },
    { key: 'adoption', label: 'Adoption', to: `/chapters/${code}/adoption` },
    { key: 'settings', label: 'Settings', to: `/chapters/${code}/settings` },
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
              <div className="eyebrow">Chapter · {ROLE_LABEL[chapter.role] ?? chapter.role}</div>
              <h1 className="page-title">{chapter.name}</h1>
              <UnderlineNav aria-label="Chapter sections">
                {tabs.map((t) => (
                  <UnderlineNav.Item key={t.key} as={Link} to={t.to} aria-current={pathname === t.to || t.key === tab ? 'page' : undefined}>
                    {t.label}
                    {t.count !== undefined && <CounterLabel>{t.count}</CounterLabel>}
                  </UnderlineNav.Item>
                ))}
              </UnderlineNav>
            </div>
          </div>
          {tab === 'overview' ? (
            <Overview chapter={chapter} members={members} sync={sync} />
          ) : tab === 'members' ? (
            <MembersTable members={members} />
          ) : tab === 'projects' && slug ? (
            <ProjectPage code={chapter.code} slug={slug} members={members} />
          ) : tab === 'projects' ? (
            <Projects code={chapter.code} canEdit={['lead', 'co_lead', 'national'].includes(chapter.role)} />
          ) : tab === 'settings' ? (
            <Settings code={chapter.code} />
          ) : (
            <Adoption code={chapter.code} />
          )}
        </>
      )}
    </>
  )
}
