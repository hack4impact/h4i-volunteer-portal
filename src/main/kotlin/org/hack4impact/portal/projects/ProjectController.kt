package org.hack4impact.portal.projects

import org.hack4impact.portal.chapters.ChapterAccess
import org.hack4impact.portal.db.tables.records.ProjectRecord
import org.hack4impact.portal.sync.SyncRequests
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** Projects (build plan step 8). Every change answers with the updated project, so the screen shows its new preview. */
@RestController
@RequestMapping("/api")
class ProjectController(
	private val access: ChapterAccess,
	private val queries: ProjectQueries,
	private val service: ProjectService,
	private val syncs: SyncRequests,
) {
	@GetMapping("/project-roles")
	fun roles(): List<ProjectRoleOption> = queries.roles()

	@GetMapping("/chapters/{code}/terms")
	fun terms(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<TermOption> = queries.terms(access.read(user, code).second.id)

	@GetMapping("/chapters/{code}/resources")
	fun resources(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<ChapterResourceOption> =
		queries.chapterResources(access.read(user, code).second.id)

	@GetMapping("/chapters/{code}/projects")
	fun list(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<ProjectSummary> = queries.list(access.read(user, code).second.id)

	@PostMapping("/chapters/{code}/projects")
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestBody input: ProjectInput): ProjectDetail {
		val (viewer, chapter) = access.manage(user, code)
		val project = service.create(chapter.id, input, viewer)
		return queries.detail(chapter.id, queries.bySlug(chapter.id, project.slug!!)!!, true)
	}

	@GetMapping("/chapters/{code}/projects/{slug}")
	fun get(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String): ProjectDetail {
		val (viewer, chapter) = access.read(user, code)
		return queries.detail(chapter.id, project(chapter.id, slug), viewer.canManage(chapter.id))
	}

	@PutMapping("/chapters/{code}/projects/{slug}")
	fun update(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: ProjectInput) =
		change(user, code, slug) { chapter, project, viewer -> service.update(chapter, project, input, viewer) }

	@PostMapping("/chapters/{code}/projects/{slug}/status")
	fun status(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: StatusChange) =
		change(user, code, slug) { chapter, project, viewer -> service.setStatus(chapter, project, input.status, viewer) }

	@PostMapping("/chapters/{code}/projects/{slug}/members")
	fun addMember(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: MemberInput) =
		change(user, code, slug) { chapter, project, viewer -> service.addMember(chapter, project, input, viewer) }

	@PutMapping("/chapters/{code}/projects/{slug}/members/{personId}")
	fun setRole(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable personId: UUID,
		@RequestBody input: MemberRoleInput,
	) = change(user, code, slug) { chapter, project, viewer -> service.setMemberRole(chapter, project, personId, input, viewer) }

	@DeleteMapping("/chapters/{code}/projects/{slug}/members/{personId}")
	fun removeMember(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable personId: UUID) =
		change(user, code, slug) { chapter, project, viewer -> service.removeMember(chapter, project, personId, viewer) }

	@PostMapping("/chapters/{code}/projects/{slug}/resources")
	fun attach(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @RequestBody input: ResourceInput) =
		change(user, code, slug) { chapter, project, viewer -> service.attachResource(chapter, project, input, viewer) }

	@PutMapping("/chapters/{code}/projects/{slug}/resources/{resourceId}")
	fun updateResource(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable resourceId: UUID,
		@RequestBody input: ResourceInput,
	) = change(user, code, slug) { chapter, project, viewer -> service.updateResource(chapter, project, resourceId, input, viewer) }

	@DeleteMapping("/chapters/{code}/projects/{slug}/resources/{resourceId}")
	fun detach(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable slug: String, @PathVariable resourceId: UUID) =
		change(user, code, slug) { chapter, project, viewer -> service.detachResource(chapter, project, resourceId, viewer) }

	/** Asks for a dry run of every tool now (through the outbox); its plan shows on the project once it finishes. */
	@PostMapping("/chapters/{code}/sync/run")
	@ResponseStatus(HttpStatus.ACCEPTED)
	fun runDryRun(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String) {
		access.manage(user, code)
		syncs.request("manual")
	}

	private fun project(chapterId: UUID, slug: String): ProjectRecord =
		queries.bySlug(chapterId, slug) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No project $slug")

	private fun change(user: OidcUser, code: String, slug: String, apply: (UUID, ProjectRecord, org.hack4impact.portal.auth.Viewer) -> Unit): ProjectDetail {
		val (viewer, chapter) = access.manage(user, code)
		val project = project(chapter.id, slug)
		if (project.status == "closed") throw ResponseStatusException(HttpStatus.CONFLICT, "This project is closed")
		apply(chapter.id, project, viewer)
		return queries.detail(chapter.id, project(chapter.id, slug), true)
	}
}
