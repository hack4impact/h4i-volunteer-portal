package org.hack4impact.portal.adoption

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.auth.requireChapter
import org.hack4impact.portal.chapters.ChapterQueries.Chapter
import org.hack4impact.portal.chapters.ChapterQueries
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.DISCOVERED_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What a lead decides about one discovered resource. [action]: link (to [target], with [projectId] for a
 * project team), unmanaged (leave it alone), or reset (back to what the naming convention says).
 */
data class AdoptionDecision(val action: String, val target: String? = null, val projectId: UUID? = null)

/** The adoption report (build plan step 7). Reading needs a role in the chapter; linking needs lead, co-lead or national. */
@RestController
@RequestMapping("/api")
class AdoptionController(
	private val viewers: Viewers,
	private val chapters: ChapterQueries,
	private val reports: AdoptionReports,
	private val dsl: DSLContext,
) {
	@GetMapping("/chapters/{code}/adoption")
	fun report(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): AdoptionReport {
		val (viewer, chapter) = authorize(user, code)
		return reports.report(chapter.id, viewer.canManage(chapter.id))
	}

	@GetMapping("/chapters/{code}/adoption/resources/{id}")
	fun people(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID): List<AdoptionPerson> {
		val (_, chapter) = authorize(user, code)
		return reports.people(chapter.id, id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not one of this chapter's resources")
	}

	/** Resources no chapter claims yet, from every tool, for leads to link. Names only. */
	@GetMapping("/adoption/unmatched")
	fun unmatched(@AuthenticationPrincipal user: OidcUser): List<UnmatchedResource> {
		val viewer = viewers.of(user)
		if (!viewer.nationalAdmin && viewer.chapterRoles.keys.none { viewer.canManage(it) }) throw AccessDeniedException("Leads only")
		return reports.unmatched()
	}

	@PostMapping("/chapters/{code}/adoption/resources/{id}")
	@Transactional
	fun decide(
		@AuthenticationPrincipal user: OidcUser,
		@PathVariable code: String,
		@PathVariable id: UUID,
		@RequestBody decision: AdoptionDecision,
	): AdoptionReport {
		val (viewer, chapter) = authorize(user, code)
		if (!viewer.canManage(chapter.id)) throw AccessDeniedException("Leads only")
		val row = dsl.selectFrom(DISCOVERED_RESOURCE).where(DISCOVERED_RESOURCE.ID.eq(id)).forUpdate().fetchOne()
			?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such resource")
		if (row.chapterId != null && row.chapterId != chapter.id && !viewer.nationalAdmin) throw AccessDeniedException("Belongs to another chapter")
		if (row.state == "adopted") throw ResponseStatusException(HttpStatus.CONFLICT, "Already adopted")
		if (row.goneAt != null) throw ResponseStatusException(HttpStatus.CONFLICT, "No longer in the tool")
		val before = describe(row.state, row.target, row.projectId)
		val now = OffsetDateTime.now()

		when (decision.action) {
			"link" -> {
				val target = decision.target?.takeIf { it in setOf("chapter_members", "chapter_leads", "project_team") }
					?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "target must be chapter_members, chapter_leads or project_team")
				if ((target == "project_team") != (decision.projectId != null)) {
					throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectId is required for project_team and only for it")
				}
				if (decision.projectId != null && !dsl.fetchExists(PROJECT, PROJECT.ID.eq(decision.projectId), PROJECT.CHAPTER_ID.eq(chapter.id))) {
					throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a project of this chapter")
				}
				dsl.update(DISCOVERED_RESOURCE)
					.set(DISCOVERED_RESOURCE.STATE, "linked").set(DISCOVERED_RESOURCE.CHAPTER_ID, chapter.id)
					.set(DISCOVERED_RESOURCE.TARGET, target).set(DISCOVERED_RESOURCE.PROJECT_ID, decision.projectId)
					.set(DISCOVERED_RESOURCE.MATCH_METHOD, "manual").set(DISCOVERED_RESOURCE.SUGGESTED_SLUG, null as String?)
					.set(DISCOVERED_RESOURCE.LINKED_BY, viewer.personId).set(DISCOVERED_RESOURCE.LINKED_AT, now)
					.where(DISCOVERED_RESOURCE.ID.eq(id)).execute()
			}
			"unmanaged" -> dsl.update(DISCOVERED_RESOURCE)
				.set(DISCOVERED_RESOURCE.STATE, "unmanaged").set(DISCOVERED_RESOURCE.CHAPTER_ID, chapter.id)
				.set(DISCOVERED_RESOURCE.TARGET, null as String?).set(DISCOVERED_RESOURCE.PROJECT_ID, null as UUID?)
				.set(DISCOVERED_RESOURCE.MATCH_METHOD, "manual")
				.set(DISCOVERED_RESOURCE.LINKED_BY, viewer.personId).set(DISCOVERED_RESOURCE.LINKED_AT, now)
				.where(DISCOVERED_RESOURCE.ID.eq(id)).execute()
			"reset" -> {
				val (chapterNames, projectNames) = names(dsl)
				applyConvention(dsl, id, NamingConvention.match(Tool.valueOf(row.tool!!.uppercase()), row.externalId!!, row.name!!, chapterNames, projectNames))
			}
			else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "action must be link, unmanaged or reset")
		}
		val after = dsl.selectFrom(DISCOVERED_RESOURCE).where(DISCOVERED_RESOURCE.ID.eq(id)).fetchSingle()
		audit(viewer, chapter, id, before, describe(after.state, after.target, after.projectId))
		return reports.report(chapter.id, true)
	}

	private fun describe(state: String?, target: String?, projectId: UUID?) =
		JSONB.valueOf("""{"state":"$state","target":${target?.let { "\"$it\"" } ?: "null"},"projectId":${projectId?.let { "\"$it\"" } ?: "null"}}""")

	private fun audit(viewer: Viewer, chapter: Chapter, id: UUID, before: JSONB, after: JSONB) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, "adoption.decide").set(AUDIT_EVENT.TARGET_TYPE, "discovered_resource").set(AUDIT_EVENT.TARGET_ID, id)
			.set(AUDIT_EVENT.CHAPTER_ID, chapter.id).set(AUDIT_EVENT.BEFORE, before).set(AUDIT_EVENT.AFTER, after)
			.execute()
	}

	/** 404 for an unknown chapter, 403 for one the viewer has no role in. */
	private fun authorize(user: OidcUser, code: String): Pair<Viewer, Chapter> {
		val viewer = viewers.of(user)
		val chapter = chapters.byCode(code) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No chapter $code")
		viewer.requireChapter(chapter.id)
		return viewer to chapter
	}
}
