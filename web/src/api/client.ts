import createClient from 'openapi-fetch'
import type { components, paths } from './schema'

export type Me = components['schemas']['Me']
export type ChapterOverview = components['schemas']['ChapterOverview']
export type MemberRow = components['schemas']['MemberRow']
export type ChapterSync = components['schemas']['ChapterSync']
export type AdoptionReport = components['schemas']['AdoptionReport']
export type AdoptionResource = components['schemas']['AdoptionResource']
export type AdoptionPerson = components['schemas']['AdoptionPerson']
export type UnmatchedResource = components['schemas']['UnmatchedResource']

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

/** Starts Google sign-in (a full-page redirect through Spring Security). */
export const signIn = () => window.location.assign('/oauth2/authorization/google')

export async function signOut() {
  const token = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.split('=')[1]
  await fetch('/logout', { method: 'POST', credentials: 'same-origin', headers: token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {} })
  window.location.assign('/')
}
