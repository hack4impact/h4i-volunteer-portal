import { api, type ToolInfo } from './client'

/**
 * The tools the portal knows (wiki: tool plugins), from `GET /api/tools`, loaded once after sign-in and before any
 * page renders. Names, order, the project page's boxes and the "#" before Slack channels all come from here.
 */
let tools: ToolInfo[] = []

/** Tool key → name ("slack" → "Slack"); filled by [setTools]. */
export const TOOL_NAME: Record<string, string> = {}

export function setTools(list: ToolInfo[]) {
  tools = [...list].sort((a, b) => a.order - b.order)
  for (const key of Object.keys(TOOL_NAME)) delete TOOL_NAME[key]
  for (const t of tools) TOOL_NAME[t.key] = t.name
}

/** Loads the tools; false when they couldn't be loaded. */
export async function loadTools(): Promise<boolean> {
  try {
    const { data } = await api.GET('/api/tools')
    if (!data) return false
    setTools(data)
    return true
  } catch {
    return false
  }
}

export const toolInfo = (key: string) => tools.find((t) => t.key === key)

/** Tools whose resources chapters attach to projects, in list order. */
export const resourceTools = () => tools.filter((t) => t.projectResources)

/** A resource's name as people see it in the tool: "#umd-rise" for a Slack channel. */
export const resourceName = (tool: string, name: string) => `${toolInfo(tool)?.namePrefix ?? ''}${name}`

/** The tool a preview's label names ("Google group" → google). */
export const toolByLabel = (label: string) => tools.find((t) => t.resourceLabel === label)?.key
