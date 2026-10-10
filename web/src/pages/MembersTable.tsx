import { Select, Spinner, TextInput } from '@primer/react'
import { DataTable, Table } from '@primer/react/experimental'
import { useMemo, useState } from 'react'
import { type MemberRow } from '../api/client'
import { ROLE_LABEL, STATUS_LABEL, STATUS_TONE, Status } from '../components/Status'

const STATE_TONE: Record<string, 'ok' | 'warn' | 'off'> = {
  confirmed: 'ok',
  unverified: 'warn',
  invited: 'warn',
  accepted: 'warn',
  suspended: 'off',
  removed: 'off',
}

/** Filters a chapter's members by text (name or email), status and chapter role ("none" = no role). */
export function filterMembers(members: MemberRow[], query: string, status: string, role: string) {
  const q = query.trim().toLowerCase()
  return members.filter(
    (m) =>
      (!q || m.name.toLowerCase().includes(q) || (m.email ?? '').toLowerCase().includes(q)) &&
      (!status || m.status === status) &&
      (!role || (role === 'none' ? !m.chapterRole : m.chapterRole === role)),
  )
}

/**
 * The members table. Chapter role and status are separate columns (design draft review: the draft mixed
 * chapter roles, project roles and status in one "Role" column).
 */
export function MembersTable({ members }: { members: MemberRow[] | null }) {
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [role, setRole] = useState('')
  const rows = useMemo(
    () => filterMembers(members ?? [], query, status, role).map((m) => ({ ...m, id: m.personId })),
    [members, query, status, role],
  )

  if (!members) return <div className="center"><Spinner aria-label="Loading members" /></div>

  return (
    <div className="page-body" style={{ gridTemplateColumns: '1fr' }}>
      <div className="box">
        <div className="box-head" id="members-title">Members</div>
        <div className="filters">
          <TextInput aria-label="Filter members" placeholder="Filter by name or email" value={query} onChange={(e) => setQuery(e.target.value)} />
          <Select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <Select.Option value="">All statuses</Select.Option>
            {Object.entries(STATUS_LABEL).map(([value, label]) => (
              <Select.Option key={value} value={value}>{label}</Select.Option>
            ))}
          </Select>
          <Select aria-label="Chapter role" value={role} onChange={(e) => setRole(e.target.value)}>
            <Select.Option value="">All roles</Select.Option>
            <Select.Option value="lead">Chapter lead</Select.Option>
            <Select.Option value="co_lead">Co-lead</Select.Option>
            <Select.Option value="viewer">Viewer</Select.Option>
            <Select.Option value="none">No chapter role</Select.Option>
          </Select>
          <span className="count">{rows.length} of {members.length}</span>
        </div>
        <div className="table-scroll">
          <Table.Container>
            <DataTable
              aria-labelledby="members-title"
              data={rows}
              columns={[
                {
                  header: 'Name',
                  field: 'name',
                  rowHeader: true,
                  renderCell: (m) => (
                    <div>
                      <strong>{m.name}</strong>
                      <div className="muted">{m.email ?? 'No H4I or school email'}{m.claimed ? '' : ' · not claimed yet'}</div>
                    </div>
                  ),
                },
                { header: 'Chapter role', field: 'chapterRole', renderCell: (m) => (m.chapterRole ? `${ROLE_LABEL[m.chapterRole]}${m.title ? ` · ${m.title}` : ''}` : '—') },
                { header: 'Status', field: 'status', renderCell: (m) => <Status tone={STATUS_TONE[m.status] ?? 'off'}>{STATUS_LABEL[m.status] ?? m.status}</Status> },
                { header: 'Projects', field: 'projects', renderCell: (m) => (m.projects.length ? m.projects.join(', ') : '—') },
                {
                  header: 'Tool accounts',
                  field: 'accounts',
                  renderCell: (m) =>
                    m.accounts.length === 0 ? (
                      <span className="muted">None linked</span>
                    ) : (
                      <span style={{ display: 'flex', flexWrap: 'wrap', gap: '4px 12px' }}>
                        {m.accounts.map((a, i) => (
                          <Status key={i} tone={STATE_TONE[a.state] ?? 'off'}>{a.tool} {a.state === 'confirmed' ? '' : `(${a.state})`}</Status>
                        ))}
                      </span>
                    ),
                },
              ]}
            />
          </Table.Container>
        </div>
      </div>
    </div>
  )
}
