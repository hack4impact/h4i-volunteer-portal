package org.hack4impact.portal.notion

/** One route: a project whose tag (or type) equals [value] goes under [parentPageId]. */
data class Route(val kind: String, val value: String, val parentPageId: String)

/** Where a project's page would go and why; [parentPageId] null = no route and no default. */
data class PagePlacement(val parentPageId: String?, val matchedBy: String?)

/** The chapter's Notion routes (PRD: chapter settings). Ordered; the first match wins, else the default parent. */
object NotionRoutes {
	fun place(tags: Collection<String>, type: String?, routes: List<Route>, defaultParent: String?): PagePlacement {
		val tagSet = tags.map { it.lowercase() }.toSet()
		val route = routes.firstOrNull { r ->
			val v = r.value.trim().lowercase()
			if (r.kind == "type") type?.trim()?.lowercase() == v else v in tagSet
		}
		return when {
			route != null -> PagePlacement(route.parentPageId, "${route.kind} ${route.value}")
			defaultParent != null -> PagePlacement(defaultParent, "default")
			else -> PagePlacement(null, null)
		}
	}

	/** The page title from the chapter's pattern; an empty {semester} drops its parentheses: "RISE DC". */
	fun title(pattern: String, project: String, semester: String?, chapter: String): String =
		pattern.replace("{project}", project).replace("{chapter}", chapter).replace("{semester}", semester.orEmpty())
			.replace(Regex("\\s*\\(\\s*\\)"), "").replace(Regex("\\s*\\[\\s*]"), "").trim()
}
