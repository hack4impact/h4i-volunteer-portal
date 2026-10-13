package org.hack4impact.portal.projects

import org.hack4impact.portal.chapters.ChapterAccess
import org.hack4impact.portal.db.tables.records.ProjectRecord
import org.hack4impact.portal.drafts.Drafts
import org.hack4impact.portal.sync.SyncRequests
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Projects (build plan step 8). Every change answers with the updated project, so the screen shows its new preview.
 * With `?draft=<id>` (step 12) reads show the draft's version and changes go into the draft instead of applying.
 */
@RestController
@RequestMapping("/api")
class ProjectController(
	private val access: ChapterAccess,
	private val queries: ProjectQueries,
	private val service: ProjectService,
	private val syncs: SyncRequests,
	private val drafts: Drafts,
	private val projectActivity: ProjectActivity,
) {
	@GetMapping("/project-roles")
	fun roles(): List<ProjectRoleOption> = queries.roles()

	@GetMapping("/chapters/{code}/terms")
	fun terms(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<TermOption> = queries.terms(access.read(user, code).second.id)

	@GetMapping("/chapters/{code}/resources")
	fun resources(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<ChapterResourceOption> =
		queries.chapterResources(access.read(user, code).second.id)

	/** With [draft]: the projects as the draft would leave them (leads and national only), each with its pending changes. */
	@GetMapping("/chapters/{code}/projects")
	fun list(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestParam(required = false) draft: UUID?): List<ProjectSummary> {
		if (draft == null) return queries.list(access.read(user, code).second.id)
		val (viewer, chapter) = access.manage(user, code)
		val pending = drafts.pendingByTarget(draft)
		return drafts.inDraft(chapter.id, draft, viewer) { queries.list(chapter.id) }.map { it.copy(pendingChanges = pending[it.id]?.size ?: 0) }
	}

	@PostMapping("/chapters/{code}/projects")
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestBody input: ProjectInput, @RequestParam(required = false) draft: UUID?): ProjectDetail {
		val (viewer, chapter) = access.manage(user, code)
		if (draft != null) {
			val op = drafts.record(chapter.id, draft, viewer, null) { drafts.operation("create_project", UUID.randomUUID(), "input" to input) }
			return inDraft(chapter.id, draft, viewer) { queries.byId(op.targetId) }
		}
		val project = service.create(chapter.id, input, viewer)
		return queries.detail(chapter.id, queries.bySlug(chapter.id, project.slug!!)!!, true)
	}

	@GetMapping("/chapters/{code}/projects/{slug}")
	fun get(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestParam(required = false) draft: UUID?): ProjectDetail {
		if (draft != null) {
			val (viewer, chapter) = access.manage(user, code)
			return inDraft(chapter.id, draft, viewer) { drafts.bySlug(chapter.id, slug) }
		}
		// The chapter's roles see every project; a project lead without one sees the projects they lead.
		val (viewer, chapter) = access.lookup(user, code)
		if (!viewer.canSee(chapter.id) && viewer.ledProjects.isEmpty()) throw AccessDeniedException("You have no role in this chapter")
		val project = project(chapter.id, slug)
		if (!viewer.canSee(chapter.id) && project.id !in viewer.ledProjects) throw AccessDeniedException("You don't lead this project")
		return queries.detail(chapter.id, project, viewer.canManage(chapter.id), viewer.canManageTeam(chapter.id, project.id!!))
	}

	/** Older activity, newest first: [before] is the oldest item already shown. */
	@GetMapping("/chapters/{code}/projects/{slug}/activity")
	fun activity(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String,
		@RequestParam(required = false) before: java.time.OffsetDateTime?, @RequestParam(defaultValue = "20") limit: Int,
	): ActivityPage {
		val (viewer, chapter) = access.lookup(user, code)
		if (!viewer.canSee(chapter.id) && viewer.ledProjects.isEmpty()) throw AccessDeniedException("You have no role in this chapter")
		val project = project(chapter.id, slug)
		if (!viewer.canSee(chapter.id) && project.id !in viewer.ledProjects) throw AccessDeniedException("You don't lead this project")
		return projectActivity.page(project.id!!, before, limit)
	}

	/** Who could join the team, for the chapter's roles and the project's leads (who can't see the chapter's member list). */
	@GetMapping("/chapters/{code}/projects/{slug}/candidates")
	fun candidates(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String): List<TeamCandidate> {
		val (viewer, chapter) = access.lookup(user, code)
		val project = if (viewer.canSee(chapter.id) || viewer.ledProjects.isNotEmpty()) project(chapter.id, slug) else throw AccessDeniedException("You have no role in this chapter")
		if (!viewer.canSee(chapter.id) && project.id !in viewer.ledProjects) throw AccessDeniedException("You don't lead this project")
		return queries.candidates(chapter.id, project.id!!)
	}

	@PutMapping("/chapters/{code}/projects/{slug}")
	fun update(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: ProjectInput, @RequestParam(required = false) draft: UUID?,
	) = change(user, code, slug, draft, { p -> drafts.operation("update_project", p.id!!, "input" to input) }) { chapter, project, viewer -> service.update(chapter, project, input, viewer) }

	@PostMapping("/chapters/{code}/projects/{slug}/status")
	fun status(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: StatusChange, @RequestParam(required = false) draft: UUID?,
	) = change(user, code, slug, draft, { p -> drafts.operation("set_status", p.id!!, "status" to input.status) }) { chapter, project, viewer -> service.setStatus(chapter, project, input.status, viewer) }

	@PostMapping("/chapters/{code}/projects/{slug}/members")
	fun addMember(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: MemberInput, @RequestParam(required = false) draft: UUID?,
	) = team(user, code, slug, draft, { p -> drafts.operation("add_member", p.id!!, "input" to input) }) { chapter, project, viewer -> service.addMember(chapter, project, input, viewer) }

	@PutMapping("/chapters/{code}/projects/{slug}/members/{personId}")
	fun setRole(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable personId: UUID,
		@RequestBody input: MemberRoleInput, @RequestParam(required = false) draft: UUID?,
	) = team(user, code, slug, draft, { p -> drafts.operation("set_member_role", p.id!!, "personId" to personId, "input" to input) }) { chapter, project, viewer ->
		service.setMemberRole(chapter, project, personId, input, viewer)
	}

	@DeleteMapping("/chapters/{code}/projects/{slug}/members/{personId}")
	fun removeMember(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable personId: UUID, @RequestParam(required = false) draft: UUID?,
	) = team(user, code, slug, draft, { p -> drafts.operation("remove_member", p.id!!, "personId" to personId) }) { chapter, project, viewer ->
		service.removeMember(chapter, project, personId, viewer)
	}

	@PostMapping("/chapters/{code}/projects/{slug}/resources")
	fun attach(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: ResourceInput, @RequestParam(required = false) draft: UUID?,
	) = change(user, code, slug, draft, { p ->
		drafts.operation("attach_resource", p.id!!, "input" to input, "newResourceId" to if (input.resourceId == null) UUID.randomUUID() else null)
	}) { chapter, project, viewer -> service.attachResource(chapter, project, input, viewer) }

	@PutMapping("/chapters/{code}/projects/{slug}/resources/{resourceId}")
	fun updateResource(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable resourceId: UUID,
		@RequestBody input: ResourceInput, @RequestParam(required = false) draft: UUID?,
	) = change(user, code, slug, draft, { p -> drafts.operation("update_resource", p.id!!, "resourceId" to resourceId, "input" to input) }) { chapter, project, viewer ->
		service.updateResource(chapter, project, resourceId, input, viewer)
	}

	@DeleteMapping("/chapters/{code}/projects/{slug}/resources/{resourceId}")
	fun detach(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable resourceId: UUID, @RequestParam(required = false) draft: UUID?,
	) = change(user, code, slug, draft, { p -> drafts.operation("detach_resource", p.id!!, "resourceId" to resourceId) }) { chapter, project, viewer ->
		service.detachResource(chapter, project, resourceId, viewer)
	}

	/** Asks for a dry run of every tool now (through the outbox); its plan shows on the project once it finishes. */
	@PostMapping("/chapters/{code}/sync/run")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun runDryRun(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String) {
		access.manage(user, code)
		syncs.request("manual")
	}

	private fun project(chapterId: UUID, slug: String): ProjectRecord =
		queries.bySlug(chapterId, slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No project $slug")

	/** The project as [draft] would leave it, with the draft's pending changes to it. */
	private fun inDraft(chapterId: UUID, draft: UUID, viewer: org.hack4impact.portal.auth.Viewer, find: () -> ProjectRecord?): ProjectDetail {
		val detail = drafts.inDraft(chapterId, draft, viewer) {
			val project = find() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such project in this draft")
			queries.detail(chapterId, project, true)
		}
		return detail.copy(pendingChanges = drafts.pendingByTarget(draft)[detail.id].orEmpty())
	}

	/**
	 * A change to the team: chapter leads and national as any change; the project's own leads too, but only directly
	 * (drafts are the chapter's leads' and national's).
	 */
	private fun team(
		user: OidcUser, code: String, slug: String, draft: UUID?, operation: (ProjectRecord) -> Drafts.Operation,
		apply: (UUID, ProjectRecord, org.hack4impact.portal.auth.Viewer) -> Unit,
	): ProjectDetail {
		val (viewer, chapter) = access.lookup(user, code)
		if (draft != null || viewer.canManage(chapter.id)) return change(user, code, slug, draft, operation, apply)
		if (viewer.ledProjects.isEmpty()) throw AccessDeniedException("Only the project's leads and the chapter's leads can change the team")
		val project = project(chapter.id, slug)
		if (project.id !in viewer.ledProjects) throw AccessDeniedException("Only the project's leads and the chapter's leads can change the team")
		if (project.status == "closed") throw ResponseStatusException(HttpStatus.CONFLICT, "This project is closed")
		apply(chapter.id, project, viewer)
		return queries.detail(chapter.id, project(chapter.id, slug), false, true)
	}

	/** Applies a change now, or with [draft] records [operation] in that draft (decision 126: both are allowed). */
	private fun change(
		user: OidcUser, code: String, slug: String, draft: UUID?, operation: (ProjectRecord) -> Drafts.Operation,
		apply: (UUID, ProjectRecord, org.hack4impact.portal.auth.Viewer) -> Unit,
	): ProjectDetail {
		val (viewer, chapter) = access.manage(user, code)
		if (draft != null) {
			val op = drafts.record(chapter.id, draft, viewer, slug) { p -> operation(p!!) }
			return inDraft(chapter.id, draft, viewer) { queries.byId(op.targetId) }
		}
		val project = project(chapter.id, slug)
		if (project.status == "closed") throw ResponseStatusException(HttpStatus.CONFLICT, "This project is closed")
		apply(chapter.id, project, viewer)
		return queries.detail(chapter.id, project(chapter.id, slug), true)
	}
}
