package org.hack4impact.portal.notion

import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode

/** A Notion page as the API reports it. [parentPageId] is null when the parent is the workspace or a teamspace. */
data class NotionPage(val id: String, val title: String, val parentPageId: String?, val archived: Boolean)

/**
 * Reads Notion pages for the chapter's Notion routes (build plan step 8): checks the integration can reach a page
 * and builds its path for the preview ("Hack4Impact-UMD / Long Term Success"). Page creation from the template is a
 * write and comes with the write adapters (step 9). Errors use the adapters' vocabulary: a page that doesn't exist
 * or isn't shared with the integration is [org.hack4impact.portal.adapters.NotFound].
 */
class NotionClient(
	private val baseUrl: String,
	private val token: String,
	private val http: HttpJson = HttpJson(Tool.NOTION),
) {
	fun page(id: String): NotionPage {
		val body = http.get("$baseUrl/pages/${HttpJson.encode(id)}", headers()).body
		val parent = body.path("parent")
		val parentPage = when (parent.text("type")) {
			"page_id" -> parent.text("page_id")
			"block_id" -> parent.text("block_id")
			else -> null
		}
		return NotionPage(body.text("id") ?: id, title(body.path("properties")), parentPage, body.path("archived").asBoolean() || body.path("in_trash").asBoolean())
	}

	/** The page's path from the top, at most [depth] levels: "Hack4Impact-UMD / Long Term Success". */
	fun path(id: String, depth: Int = 6): String {
		val titles = ArrayDeque<String>()
		var next: String? = id
		while (next != null && titles.size < depth) {
			val page = page(next)
			titles.addFirst(page.title.ifBlank { "Untitled" })
			next = page.parentPageId
		}
		if (next != null) titles.addFirst("…")
		return titles.joinToString(" / ")
	}

	private fun title(properties: JsonNode): String {
		val property = properties.properties().map { it.value }.firstOrNull { it.text("type") == "title" } ?: return ""
		return buildString { property.path("title").forEach { append(it.text("plain_text").orEmpty()) } }
	}

	private fun headers() = mapOf("Authorization" to "Bearer $token", "Notion-Version" to "2022-06-28")

	companion object {
		private val HEX32 = Regex("[0-9a-fA-F]{32}")

		/** A page ID from a Notion URL or an ID with or without dashes, as Notion's dashed form; null if there's none. */
		fun pageId(input: String): String? {
			val raw = input.trim().substringBefore('?').substringBefore('#')
			val hex = HEX32.findAll(raw.replace("-", "")).lastOrNull()?.value?.lowercase() ?: return null
			return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
		}
	}
}
