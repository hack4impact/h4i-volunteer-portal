package org.hack4impact.portal.notion

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.AuthFailed
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.chapters.ChapterAccess
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.NOTION_PAGE_CHECK
import org.hack4impact.portal.db.tables.references.NOTION_ROUTE
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

/** A page the settings point at, with its latest check: [path] if the integration reached it, else [error]. Both null = not checked. */
data class NotionPageRef(val pageId: String, val path: String?, val error: String?, val checkedAt: OffsetDateTime?)

data class NotionRouteView(val kind: String, val value: String, val parent: NotionPageRef)

data class NotionSettingsView(
	/** Whether a Notion integration is set up, so pages can be checked. */
	val configured: Boolean,
	val teamspaceId: String?,
	val defaultParent: NotionPageRef?,
	val template: NotionPageRef?,
	val titlePattern: String,
	val routes: List<NotionRouteView>,
	/** Pages the integration can't reach: shown on the chapter overview (PRD: broken routes are flagged). */
	val problems: Int,
	val canEdit: Boolean,
)

/** Pages are given as Notion URLs or IDs. [kind] is tag or type. */
data class NotionRouteInput(val kind: String, val value: String, val parent: String)

data class NotionSettingsInput(
	val teamspaceId: String? = null,
	val defaultParent: String? = null,
	val template: String? = null,
	val titlePattern: String = "{project} ({semester})",
	val routes: List<NotionRouteInput> = emptyList(),
)

@Component
class NotionSettings(private val dsl: DSLContext, private val client: ObjectProvider<NotionClient>) {
	fun view(chapterId: UUID, canEdit: Boolean): NotionSettingsView {
		val chapter = dsl.select(CHAPTER.NOTION_TEAMSPACE_ID, CHAPTER.NOTION_DEFAULT_PARENT_ID, CHAPTER.NOTION_TEMPLATE_PAGE_ID, CHAPTER.NOTION_TITLE_PATTERN)
			.from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle()
		val routes = routes(chapterId)
		val checks = checks(routes.map { it.parentPageId } + listOfNotNull(chapter.value2(), chapter.value3()))
		val ref = { id: String -> checks[id] ?: NotionPageRef(id, null, null, null) }
		val view = NotionSettingsView(
			configured = client.ifAvailable != null,
			teamspaceId = chapter.value1(),
			defaultParent = chapter.value2()?.let(ref),
			template = chapter.value3()?.let(ref),
			titlePattern = chapter.value4()!!,
			routes = routes.map { NotionRouteView(it.kind, it.value, ref(it.parentPageId)) },
			problems = 0,
			canEdit = canEdit,
		)
		return view.copy(problems = (view.routes.map { it.parent } + listOfNotNull(view.defaultParent, view.template)).count { it.error != null })
	}

	fun routes(chapterId: UUID): List<Route> =
		dsl.selectFrom(NOTION_ROUTE).where(NOTION_ROUTE.CHAPTER_ID.eq(chapterId)).orderBy(NOTION_ROUTE.POSITION)
			.fetch { Route(it.matchKind!!, it.matchValue!!, it.parentPageId!!) }

	fun checks(pageIds: Collection<String>): Map<String, NotionPageRef> =
		if (pageIds.isEmpty()) emptyMap()
		else dsl.selectFrom(NOTION_PAGE_CHECK).where(NOTION_PAGE_CHECK.PAGE_ID.`in`(pageIds.toSet()))
			.fetch { NotionPageRef(it.pageId!!, it.path, it.error, it.checkedAt) }.associateBy { it.pageId }

	@Transactional
	fun save(chapterId: UUID, input: NotionSettingsInput, viewer: Viewer) {
		fun id(value: String?, what: String): String? = value?.takeIf { it.isNotBlank() }?.let {
			NotionClient.pageId(it) ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$what isn't a Notion page link or ID")
		}
		if (input.titlePattern.isBlank() || "{project}" !in input.titlePattern) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The title pattern needs {project}")
		val routes = input.routes.mapIndexed { i, r ->
			if (r.kind !in setOf("tag", "type")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Route ${i + 1}: match a tag or a type")
			if (r.value.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Route ${i + 1}: which ${r.kind}?")
			Route(r.kind, r.value.trim().lowercase(), id(r.parent, "Route ${i + 1}'s page")!!)
		}
		dsl.update(CHAPTER)
			.set(CHAPTER.NOTION_TEAMSPACE_ID, input.teamspaceId?.trim()?.takeIf { it.isNotEmpty() })
			.set(CHAPTER.NOTION_DEFAULT_PARENT_ID, id(input.defaultParent, "The default page"))
			.set(CHAPTER.NOTION_TEMPLATE_PAGE_ID, id(input.template, "The template page"))
			.set(CHAPTER.NOTION_TITLE_PATTERN, input.titlePattern.trim())
			.where(CHAPTER.ID.eq(chapterId)).execute()
		teamspaceResource(chapterId, input.teamspaceId?.trim()?.takeIf { it.isNotEmpty() })
		dsl.deleteFrom(NOTION_ROUTE).where(NOTION_ROUTE.CHAPTER_ID.eq(chapterId)).execute()
		routes.forEachIndexed { i, r ->
			dsl.insertInto(NOTION_ROUTE).set(NOTION_ROUTE.CHAPTER_ID, chapterId).set(NOTION_ROUTE.POSITION, i + 1)
				.set(NOTION_ROUTE.MATCH_KIND, r.kind).set(NOTION_ROUTE.MATCH_VALUE, r.value).set(NOTION_ROUTE.PARENT_PAGE_ID, r.parentPageId).execute()
		}
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, "chapter.notion_routes").set(AUDIT_EVENT.TARGET_TYPE, "chapter").set(AUDIT_EVENT.TARGET_ID, chapterId)
			.set(AUDIT_EVENT.CHAPTER_ID, chapterId).set(AUDIT_EVENT.AFTER, JSONB.valueOf("""{"routes":${routes.size}}"""))
			.execute()
	}

	/**
	 * The chapter's teamspace as a chapter resource for its members, so the sync queues "add to the teamspace" tasks
	 * (PRD: access is per chapter teamspace). Changing the ID retargets it; clearing it archives it.
	 */
	private fun teamspaceResource(chapterId: UUID, teamspaceId: String?) {
		val existing = dsl.select(RESOURCE.ID).from(RESOURCE)
			.where(RESOURCE.CHAPTER_ID.eq(chapterId), RESOURCE.TOOL.eq("notion"), RESOURCE.TAGS.contains(arrayOf("teamspace")), RESOURCE.ARCHIVED_AT.isNull)
			.fetchOne()?.value1()
		if (teamspaceId == null) {
			existing?.let {
				dsl.deleteFrom(CHAPTER_RESOURCE).where(CHAPTER_RESOURCE.RESOURCE_ID.eq(it)).execute()
				dsl.update(RESOURCE).set(RESOURCE.ARCHIVED_AT, OffsetDateTime.now()).where(RESOURCE.ID.eq(it)).execute()
			}
			return
		}
		val name = dsl.select(CHAPTER.NAME).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle().value1()!!
		if (existing != null) {
			dsl.update(RESOURCE).set(RESOURCE.EXTERNAL_ID, teamspaceId).set(RESOURCE.NAME, name).where(RESOURCE.ID.eq(existing)).execute()
			return
		}
		val id = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "notion").set(RESOURCE.EXTERNAL_ID, teamspaceId).set(RESOURCE.NAME, name)
			.set(RESOURCE.CHAPTER_ID, chapterId).set(RESOURCE.TAGS, arrayOf("teamspace")).set(RESOURCE.MANAGED, "portal")
			.returningResult(RESOURCE.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_RESOURCE).set(CHAPTER_RESOURCE.CHAPTER_ID, chapterId).set(CHAPTER_RESOURCE.RESOURCE_ID, id)
			.set(CHAPTER_RESOURCE.AUDIENCE, "members").set(CHAPTER_RESOURCE.ACCESS_LEVEL, "write").execute()
	}

	/** Checks every page the chapter's settings point at, if a Notion integration is set up. */
	fun check(chapterId: UUID) {
		val notion = client.ifAvailable ?: return
		val chapter = dsl.select(CHAPTER.NOTION_DEFAULT_PARENT_ID, CHAPTER.NOTION_TEMPLATE_PAGE_ID).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle()
		val pages = (routes(chapterId).map { it.parentPageId } + listOfNotNull(chapter.value1(), chapter.value2())).toSet()
		for (page in pages) {
			val (path, error) = try {
				notion.path(page) to null
			} catch (e: NotFound) {
				null to "The integration can't see this page: share it with the integration, or it was moved or deleted"
			} catch (e: AuthFailed) {
				null to "The Notion integration was disconnected or its secret is wrong"
			} catch (e: AdapterException) {
				null to (e.message ?: "Notion error")
			}
			dsl.insertInto(NOTION_PAGE_CHECK).set(NOTION_PAGE_CHECK.PAGE_ID, page).set(NOTION_PAGE_CHECK.PATH, path).set(NOTION_PAGE_CHECK.ERROR, error)
				.onConflict(NOTION_PAGE_CHECK.PAGE_ID).doUpdate()
				.set(NOTION_PAGE_CHECK.PATH, path).set(NOTION_PAGE_CHECK.ERROR, error).set(NOTION_PAGE_CHECK.CHECKED_AT, OffsetDateTime.now())
				.execute()
		}
	}
}

/** Chapter settings: Notion routes. Leads and co-leads edit; saving checks every page (PRD: validation with a preview path). */
@RestController
@RequestMapping("/api/chapters/{code}/notion")
class NotionSettingsController(private val access: ChapterAccess, private val settings: NotionSettings) {
	@GetMapping
	fun get(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): NotionSettingsView {
		val (viewer, chapter) = access.read(user, code)
		return settings.view(chapter.id, viewer.canManage(chapter.id))
	}

	@PutMapping
	fun put(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestBody input: NotionSettingsInput): NotionSettingsView {
		val (viewer, chapter) = access.manage(user, code)
		settings.save(chapter.id, input, viewer)
		settings.check(chapter.id)
		return settings.view(chapter.id, true)
	}

	@PostMapping("/check")
	fun check(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): NotionSettingsView {
		val (_, chapter) = access.manage(user, code)
		settings.check(chapter.id)
		return settings.view(chapter.id, true)
	}
}
