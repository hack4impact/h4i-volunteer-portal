import { useEffect, useState } from 'react'
import { api } from './client'

/**
 * The draft a lead is planning in, per chapter (build plan step 12). While one is open, the chapter's project requests
 * carry `?draft=`, so the project screens show and change the draft instead of live data. Kept for the browser tab only.
 */
const key = (code: string) => `h4i-draft:${code}`
const CHANGED = 'h4i-draft'

export function activeDraft(code: string): string | null {
  try { return sessionStorage.getItem(key(code)) } catch { return null }
}

export function setActiveDraft(code: string, id: string | null) {
  try {
    if (id) sessionStorage.setItem(key(code), id)
    else sessionStorage.removeItem(key(code))
  } catch { /* private mode: drafts still work from the Drafts tab */ }
  window.dispatchEvent(new Event(CHANGED))
}

/** [draft id or null, a counter that moves whenever the draft gets a change] for re-rendering. */
export function useActiveDraft(code: string): [string | null, number] {
  const [state, setState] = useState<[string | null, number]>([activeDraft(code), 0])
  useEffect(() => {
    const update = () => setState(([, n]) => [activeDraft(code), n + 1])
    window.addEventListener(CHANGED, update)
    return () => window.removeEventListener(CHANGED, update)
  }, [code])
  return state
}

const PROJECTS = /^\/api\/chapters\/([^/]+)\/projects(\/|$)/

api.use({
  async onRequest({ request }) {
    const url = new URL(request.url)
    const chapter = url.pathname.match(PROJECTS)?.[1]
    const draft = chapter ? activeDraft(chapter) : null
    if (!draft) return request
    url.searchParams.set('draft', draft)
    const body = request.method === 'GET' || request.method === 'HEAD' ? undefined : await request.clone().arrayBuffer()
    return new Request(url, { method: request.method, headers: request.headers, body, credentials: request.credentials })
  },
  onResponse({ request, response }) {
    // A change went into the draft: the banner's count follows.
    if (request.method !== 'GET' && new URL(request.url).searchParams.has('draft') && response.ok) window.dispatchEvent(new Event(CHANGED))
    return response
  },
})
