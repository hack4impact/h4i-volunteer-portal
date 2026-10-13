import createClient from 'openapi-fetch'
import type { components, paths } from './schema'

export type Me = components['schemas']['Me']
export type ChapterOverview = components['schemas']['ChapterOverview']
export type MemberRow = components['schemas']['MemberRow']
export type ChapterSync = components['schemas']['ChapterSync']
export type ProjectSummary = components['schemas']['ProjectSummary']
export type ProjectDetail = components['schemas']['ProjectDetail']
export type ProjectResourceRow = components['schemas']['ProjectResourceRow']
export type ProjectRoleOption = components['schemas']['ProjectRoleOption']
export type TermOption = components['schemas']['TermOption']
export type ChapterResourceOption = components['schemas']['ChapterResourceOption']
export type NotionSettingsView = components['schemas']['NotionSettingsView']
export type NotionPageRef = components['schemas']['NotionPageRef']
export type PlannedChange = components['schemas']['PlannedChange']
export type ToolStatus = components['schemas']['ToolStatus']
export type QueueTask = components['schemas']['QueueTask']
export type DeadLetter = components['schemas']['DeadLetter']
export type ApplicantView = components['schemas']['ApplicantView']
export type ApplyChapter = components['schemas']['ApplyChapter']
export type RegistrationSummary = components['schemas']['RegistrationSummary']
export type RegistrationSettings = components['schemas']['RegistrationSettings']
export type RosterInviteView = components['schemas']['RosterInviteView']
export type ClaimPreview = components['schemas']['ClaimPreview']
export type AdoptionReport = components['schemas']['AdoptionReport']
export type AdoptionResource = components['schemas']['AdoptionResource']
export type AdoptionPerson = components['schemas']['AdoptionPerson']
export type UnmatchedResource = components['schemas']['UnmatchedResource']
export type AgreementTemplates = components['schemas']['AgreementTemplates']
export type AgreementTemplateView = components['schemas']['AgreementTemplateView']
export type MemberAgreement = components['schemas']['MemberAgreement']
export type ApplicantAgreement = components['schemas']['ApplicantAgreement']
export type DraftSummary = components['schemas']['DraftSummary']
export type DraftDetail = components['schemas']['DraftDetail']
export type DraftPreview = components['schemas']['DraftPreview']
export type TeamCandidate = components['schemas']['TeamCandidate']
export type ActivityItem = components['schemas']['ActivityItem']
export type MemberHome = components['schemas']['MemberHome']
export type ReleaseResult = components['schemas']['ReleaseResult']
export type RuleInput = components['schemas']['RuleInput']
export type RuleView = components['schemas']['RuleView']
export type RulesPage = components['schemas']['RulesPage']
export type RulePreview = components['schemas']['RulePreview']
export type RulePreviewInput = components['schemas']['RulePreviewInput']
export type MyAccess = components['schemas']['MyAccess']

/** Typed client for the portal API; types come from web/openapi.json (`npm run gen:api`). */
export const api = createClient<paths>({
  baseUrl: window.location.origin,
  credentials: 'same-origin',
  // Look fetch up on each call (not once at startup), so tests can swap it.
  fetch: (request) => globalThis.fetch(request),
})

// Spring Security keeps the CSRF token in the XSRF-TOKEN cookie and expects it back on anything but GET.
api.use({
  onRequest({ request }) {
    if (request.method !== 'GET') {
      const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1]
      if (token) request.headers.set('X-XSRF-TOKEN', decodeURIComponent(token))
    }
    return request
  },
})

/** The server's reason for refusing a request (problem details), or a plain fallback. */
export function reason(error: unknown, response: Response, fallback = 'That didn\'t work.') {
  const detail = error && typeof error === 'object' && 'detail' in error ? (error as { detail?: unknown }).detail : undefined
  if (typeof detail === 'string' && detail) return detail
  if (response.status === 403) return 'Only chapter leads and co-leads can change this.'
  return `${fallback} (HTTP ${response.status})`
}

/** Starts Google sign-in (a full-page redirect through Spring Security). */
export const signIn = () => window.location.assign('/oauth2/authorization/google')

export async function signOut() {
  const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1]
  await fetch('/logout', { method: 'POST', credentials: 'same-origin', headers: token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {} })
  window.location.assign('/')
}
