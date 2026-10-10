package org.hack4impact.portal.adoption

import org.hack4impact.portal.resolver.Tool
import java.util.UUID

/** Who an adopted resource would be for. */
enum class Target { CHAPTER_MEMBERS, CHAPTER_LEADS, PROJECT_TEAM }

data class ChapterNames(val id: UUID, val code: String, val prefixes: Set<String> = emptySet())

data class ProjectNames(val id: UUID, val chapterId: UUID, val slug: String)

/**
 * A convention match. [target] is null when the chapter is clear but the rest of the name isn't a known
 * project; [suggestedSlug] then holds that rest, e.g. "rise-dc" for a project not created yet.
 */
data class ConventionMatch(val chapterId: UUID, val target: Target?, val projectId: UUID? = null, val suggestedSlug: String? = null)

/**
 * The PRD's naming convention (build plan step 7): `#umd-rise-dc`, team `umd-rise-dc`, group `umd-rise-dc@`.
 *
 *  - `<chapter>` alone: the chapter's members (`#umd`, `umd@`)
 *  - `<chapter>-leads`: the chapter's leads
 *  - `<chapter>-<project slug>`, optionally with a suffix (`umd-rise-dc-dev`): that project's team
 *  - any other `<chapter>-…`: the chapter, with what follows as a suggested project slug
 *
 * The chapter part is the chapter code or one of its extra prefixes; the longest one wins, and so does the
 * longest project slug. Case, spaces and underscores don't matter.
 */
object NamingConvention {
	fun match(tool: Tool, externalId: String, name: String, chapters: List<ChapterNames>, projects: List<ProjectNames>): ConventionMatch? {
		val key = key(tool, externalId, name)
		val (chapter, prefix) = chapters
			.flatMap { c -> (c.prefixes + c.code).map { c to normalize(it) } }
			.filter { (_, p) -> p.isNotEmpty() && (key == p || key.startsWith("$p-")) }
			.maxByOrNull { (_, p) -> p.length }
			?: return null
		val rest = key.removePrefix(prefix).removePrefix("-")
		if (rest.isEmpty()) return ConventionMatch(chapter.id, Target.CHAPTER_MEMBERS)
		if (rest == "leads") return ConventionMatch(chapter.id, Target.CHAPTER_LEADS)
		val project = projects
			.filter { it.chapterId == chapter.id && (rest == it.slug || rest.startsWith("${it.slug}-")) }
			.maxByOrNull { it.slug.length }
		return if (project != null) ConventionMatch(chapter.id, Target.PROJECT_TEAM, project.id)
		else ConventionMatch(chapter.id, null, suggestedSlug = rest)
	}

	/** What the convention applies to: a group's address before the @, a team's slug, a channel's name. */
	fun key(tool: Tool, externalId: String, name: String): String = normalize(
		when (tool) {
			Tool.GOOGLE -> externalId.substringBefore('@')
			Tool.GITHUB -> externalId
			else -> name
		},
	)

	private fun normalize(s: String) = s.trim().trimStart('#').lowercase().replace(Regex("[\\s_]+"), "-").trim('-')
}
