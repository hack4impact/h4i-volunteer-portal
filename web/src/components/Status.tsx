/** Status as a colored dot plus words (design system: status is text first; color never alone). */
export function Status({ tone, children }: { tone: 'ok' | 'warn' | 'off' | 'danger'; children: React.ReactNode }) {
  return <span className={`status ${tone}`}>{children}</span>
}

export const STATUS_LABEL: Record<string, string> = {
  applicant: 'Applicant',
  active: 'Active',
  alumni: 'Alumni',
  removal_requested: 'Removal requested',
  removed: 'Removed',
}

export const STATUS_TONE: Record<string, 'ok' | 'warn' | 'off' | 'danger'> = {
  applicant: 'warn',
  active: 'ok',
  alumni: 'off',
  removal_requested: 'danger',
  removed: 'off',
}

export const ROLE_LABEL: Record<string, string> = {
  lead: 'Chapter lead',
  co_lead: 'Co-lead',
  viewer: 'Viewer',
  national: 'National admin',
}

export const TOOL_NAME: Record<string, string> = {
  google: 'Google',
  github: 'GitHub',
  slack: 'Slack',
  notion: 'Notion',
  vaultwarden: 'Vaultwarden',
  documenso: 'Documenso',
}

/** Planned sync changes, in words. */
export const CHANGE_LABEL: Record<string, string> = {
  add: 'Add',
  change: 'Change access',
  remove: 'Remove',
  drift: 'Not granted by the portal',
  unmatched_account: 'Account not linked to anyone',
  missing_resource: 'Missing in the tool',
  create_resource: 'Create in the tool',
}

export const PROJECT_STATUS: Record<string, [string, 'ok' | 'warn' | 'off' | 'danger']> = {
  draft: ['Draft', 'warn'],
  active: ['Active', 'ok'],
  paused: ['Paused', 'off'],
  closed: ['Closed', 'off'],
}

/** A resource's name as people see it in the tool: Slack channels with #. */
export const resourceName = (tool: string, name: string) => (tool === 'slack' ? `#${name}` : name)
