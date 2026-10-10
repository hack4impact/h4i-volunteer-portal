import { Banner, CounterLabel, Spinner, UnderlineNav } from '@primer/react'
import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { api, type ChapterOverview, type ChapterSync, type Me, type MemberRow } from '../api/client'
import { ROLE_LABEL } from '../components/Status'
import { TopBar } from '../components/TopBar'
import { Adoption } from './Adoption'
import { MembersTable } from './MembersTable'
import { Overview } from './Overview'

/** One chapter: page header, tabs, and the tab's content. Everything here is read-only in step 5. */
export function ChapterPage({ me, tab }: { me: Me; tab: 'overview' | 'members' | 'adoption' }) {
  const { code = '' } = useParams()
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
    { key: 'adoption', label: 'Adoption', to: `/chapters/${code}/adoption` },
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
          ) : (
            <Adoption code={chapter.code} />
          )}
        </>
      )}
    </>
  )
}
