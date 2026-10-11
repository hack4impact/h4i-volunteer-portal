package org.hack4impact.portal.notion

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.projects.ProjectQueries
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Project pages in Notion (PRD; build plan step 9): a live project without a page gets one under its chapter's
 * route, titled by the chapter's pattern and copied from its template; a closed project's page goes to the trash.
 * Needs `PORTAL_ADAPTERS_NOTION_WRITE=true` and an integration that may insert content, and runs only when the
 * Notion tool is out of dry run. A failure is logged and audited and tried again at the next sync.
 */
@Component
class NotionPages(
	private val dsl: DSLContext,
	private val settings: NotionSettings,
	private val client: ObjectProvider<NotionClient>,
	@Value("\${portal.adapters.notion.write:false}") private val write: Boolean,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun canWrite() = write && client.ifAvailable != null

	data class Result(val created: Int, val archived: Int, val failed: Int)

	fun sync(): Result {
		val notion = client.ifAvailable ?: return Result(0, 0, 0)
		var created = 0
		var archived = 0
		var failed = 0
		val live = dsl.select(PROJECT.ID, PROJECT.CHAPTER_ID, PROJECT.NAME, PROJECT.TAGS, PROJECT.TYPE, TERM.SEASON, TERM.YEAR, CHAPTER.CODE, CHAPTER.NOTION_DEFAULT_PARENT_ID,
			CHAPTER.NOTION_TEMPLATE_PAGE_ID, CHAPTER.NOTION_TITLE_PATTERN)
			.from(PROJECT).join(CHAPTER).on(CHAPTER.ID.eq(PROJECT.CHAPTER_ID)).leftJoin(TERM).on(TERM.ID.eq(PROJECT.TERM_ID))
			.where(PROJECT.STATUS.`in`("active", "paused"), PROJECT.NOTION_PAGE_ID.isNull)
			.fetch()
		for (p in live) {
			val placement = NotionRoutes.place(p.value4()!!.filterNotNull(), p.value5(), settings.routes(p.value2()!!), p.value9())
			val parent = placement.parentPageId ?: continue // no route: the project page says so
			val title = NotionRoutes.title(p.value11()!!, p.value3()!!, ProjectQueries.termLabel(p.value6(), p.value7()?.toInt()), p.value8()!!)
			try {
				val page = notion.createPage(parent, title, p.value10())
				dsl.update(PROJECT).set(PROJECT.NOTION_PAGE_ID, page).where(PROJECT.ID.eq(p.value1())).execute()
				audit("notion.page_create", p.value1()!!, p.value2(), "Created the Notion page “$title”")
				created++
			} catch (e: AdapterException) {
				failed++
				log.warn("Notion page for {} not created: {}", p.value3(), e.message)
				audit("notion.page_failed", p.value1()!!, p.value2(), "Couldn't create the Notion page: ${e.message}")
			}
		}
		val closed = dsl.select(PROJECT.ID, PROJECT.CHAPTER_ID, PROJECT.NOTION_PAGE_ID).from(PROJECT)
			.where(PROJECT.STATUS.eq("closed"), PROJECT.NOTION_PAGE_ID.isNotNull, PROJECT.NOTION_PAGE_ARCHIVED_AT.isNull).fetch()
		for (p in closed) {
			try {
				notion.archivePage(p.value3()!!)
				dsl.update(PROJECT).set(PROJECT.NOTION_PAGE_ARCHIVED_AT, OffsetDateTime.now()).where(PROJECT.ID.eq(p.value1())).execute()
				audit("notion.page_archive", p.value1()!!, p.value2(), "Moved the Notion page to the trash")
				archived++
			} catch (e: AdapterException) {
				failed++
				log.warn("Notion page {} not archived: {}", p.value3(), e.message)
			}
		}
		return Result(created, archived, failed)
	}

	private fun audit(action: String, project: java.util.UUID, chapter: java.util.UUID?, summary: String) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, "sync").set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "project").set(AUDIT_EVENT.TARGET_ID, project)
			.set(AUDIT_EVENT.CHAPTER_ID, chapter)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(mapOf("summary" to summary))))
			.execute()
	}
}
