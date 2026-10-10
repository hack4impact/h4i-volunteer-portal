import { ActionList, ActionMenu } from '@primer/react'
import { useNavigate } from 'react-router'
import { type Me, signOut } from '../api/client'

function initials(me: Me) {
  const source = me.name ?? me.email
  return source.split(/[\s@.]+/).filter(Boolean).slice(0, 2).map((s) => s[0]!.toUpperCase()).join('')
}

/** Header: wordmark, chapter switcher (only chapters the user has a role in), account menu. */
export function TopBar({ me, chapterCode }: { me: Me; chapterCode?: string }) {
  const navigate = useNavigate()
  const current = me.chapters.find((c) => c.code === chapterCode)
  return (
    <header className="topbar">
      <a className="wordmark" href="/">
        hack<span>4</span>impact
      </a>
      {me.chapters.length > 0 && (
        <>
          <span className="divider" />
          <ActionMenu>
            <ActionMenu.Button aria-label="Switch chapter">{current?.code.toUpperCase() ?? 'Chapters'}</ActionMenu.Button>
            <ActionMenu.Overlay width="medium">
              <ActionList selectionVariant="single">
                {me.chapters.map((c) => (
                  <ActionList.Item key={c.id} selected={c.code === chapterCode} onSelect={() => navigate(`/chapters/${c.code}`)}>
                    {c.name}
                  </ActionList.Item>
                ))}
              </ActionList>
            </ActionMenu.Overlay>
          </ActionMenu>
        </>
      )}
      <span className="spacer" />
      <ActionMenu>
        <ActionMenu.Anchor>
          <button className="initials" aria-label={`Account: ${me.email}`} style={{ border: 0, cursor: 'pointer' }}>
            {initials(me)}
          </button>
        </ActionMenu.Anchor>
        <ActionMenu.Overlay>
          <ActionList>
            <ActionList.Group>
              <ActionList.GroupHeading>{me.email}</ActionList.GroupHeading>
            </ActionList.Group>
            <ActionList.Divider />
            <ActionList.Item onSelect={() => void signOut()}>Sign out</ActionList.Item>
          </ActionList>
        </ActionMenu.Overlay>
      </ActionMenu>
    </header>
  )
}
