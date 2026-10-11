package org.hack4impact.portal.notion

import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode

/** A Notion page as the API reports it. [parentPageId] is null when the parent is the workspace or a teamspace. */
data class NotionPage(val id: String, val title: String, val parentPageId: String?, val archived: Boolean)

/**
 * Reads Notion pages for the chapter's Notion routes (build plan step 8): checks the integration can reach a page
 * and builds its path for the preview ("Hack4Impact-UMD / Long Term Success"). With an integration that may insert
 * content (step 9), it also creates project pages from the template and moves closed projects' pages to the trash.
 * Errors use the adapters' vocabulary: a page that doesn't exist or isn't shared with the integration is
 * [org.hack4impact.portal.adapters.NotFound].
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

	/**
	 * Creates a page titled [title] under [parentId], with a copy of [templateId]'s content when given: its blocks,
	 * two levels deep (what one request can nest). Blocks the API can't recreate (child pages and databases, synced
	 * blocks, link previews) are left out. Returns the new page's ID.
	 */
	fun createPage(parentId: String, title: String, templateId: String?): String {
		val blocks = templateId?.let { copyable(it, depth = 0) }.orEmpty()
		val page = http.postJson(
			"$baseUrl/pages",
			mapOf(
				"parent" to mapOf("page_id" to parentId),
				"properties" to mapOf("title" to mapOf("title" to listOf(mapOf("text" to mapOf("content" to title))))),
				"children" to blocks.take(100),
			),
			headers(),
		).body.text("id")!!
		// A create request takes at most 100 blocks; the rest are appended in batches.
		blocks.drop(100).chunked(100).forEach { http.patchJson("$baseUrl/blocks/$page/children", mapOf("children" to it), headers()) }
		return page
	}

	/** Moves a page to Notion's trash (restorable there). Gone already counts as done. */
	fun archivePage(id: String) {
		try {
			http.patchJson("$baseUrl/pages/${HttpJson.encode(id)}", mapOf("archived" to true), headers())
		} catch (e: org.hack4impact.portal.adapters.NotFound) {
			// Already deleted, or no longer shared with the integration.
		}
	}

	/** A block's children as blocks to create: only each block's type and its content, children nested below. */
	private fun copyable(blockId: String, depth: Int): List<Map<String, Any?>> {
		val out = mutableListOf<Map<String, Any?>>()
		var cursor: String? = null
		do {
			val body = http.get("$baseUrl/blocks/${HttpJson.encode(blockId)}/children?page_size=100" + (cursor?.let { "&start_cursor=${HttpJson.encode(it)}" } ?: ""), headers()).body
			for (block in body.path("results")) {
				val type = block.text("type") ?: continue
				if (type in UNCOPYABLE) continue
				@Suppress("UNCHECKED_CAST")
				val content = (HttpJson.MAPPER.convertValue(block.path(type), Map::class.java) as Map<String, Any?>).toMutableMap()
				if (block.path("has_children").asBoolean() && depth < 1) content["children"] = copyable(block.text("id")!!, depth + 1)
				out += mapOf("object" to "block", "type" to type, type to content)
			}
			cursor = body.text("next_cursor")?.takeIf { body.path("has_more").asBoolean() }
		} while (cursor != null)
		return out
	}

	private fun title(properties: JsonNode): String {
		val property = properties.properties().map { it.value }.firstOrNull { it.text("type") == "title" } ?: return ""
		return buildString { property.path("title").forEach { append(it.text("plain_text").orEmpty()) } }
	}

	private fun headers() = mapOf("Authorization" to "Bearer $token", "Notion-Version" to "2022-06-28")

	companion object {
		private val HEX32 = Regex("[0-9a-fA-F]{32}")
		private val UNCOPYABLE = setOf("child_page", "child_database", "synced_block", "link_preview", "unsupported", "template", "ai_block")

		/** A page ID from a Notion URL or an ID with or without dashes, as Notion's dashed form; null if there's none. */
		fun pageId(input: String): String? {
			val raw = input.trim().substringBefore('?').substringBefore('#')
			val hex = HEX32.findAll(raw.replace("-", "")).lastOrNull()?.value?.lowercase() ?: return null
			return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
		}
	}
}
