package org.hack4impact.portal.drafts

import org.hack4impact.portal.chapters.ChapterAccess
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
import java.util.UUID

/**
 * A chapter's drafts (build plan step 12). PRD: only the chapter's leads and national see drafts; members, project
 * leads and viewers see nothing until release. Project changes go into a draft through the project API with `?draft=`.
 */
@RestController
@RequestMapping("/api/chapters/{code}/drafts")
class DraftController(private val access: ChapterAccess, private val drafts: Drafts) {
	@GetMapping
	fun list(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<DraftSummary> =
		drafts.list(access.manage(user, code).second.id)

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestBody input: DraftInput): DraftSummary {
		val (viewer, chapter) = access.manage(user, code)
		return drafts.create(chapter.id, input.name, viewer)
	}

	@GetMapping("/{id}")
	fun get(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID): DraftDetail {
		val (viewer, chapter) = access.manage(user, code)
		return drafts.detail(chapter.id, id, viewer)
	}

	@PutMapping("/{id}")
	fun rename(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID, @RequestBody input: DraftInput) =
		act(user, code, id) { chapter, viewer -> drafts.rename(chapter, id, input.name, viewer) }

	@DeleteMapping("/{id}/changes/{changeId}")
	fun removeChange(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID, @PathVariable changeId: UUID) =
		act(user, code, id) { chapter, viewer -> drafts.removeChange(chapter, id, changeId, viewer) }

	@PostMapping("/{id}/schedule")
	fun schedule(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID, @RequestBody input: ScheduleInput) =
		act(user, code, id) { chapter, viewer -> drafts.schedule(chapter, id, input.at, viewer) }

	@PostMapping("/{id}/unschedule")
	fun unschedule(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID) =
		act(user, code, id) { chapter, viewer -> drafts.unschedule(chapter, id, viewer) }

	@PostMapping("/{id}/discard")
	fun discard(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID) =
		act(user, code, id) { chapter, viewer -> drafts.discard(chapter, id, viewer) }

	/** Releases now. With conflicts nothing changes, and [ReleaseResult.conflicts] says what to fix. */
	@PostMapping("/{id}/release")
	fun release(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID): ReleaseResult {
		val (viewer, chapter) = access.manage(user, code)
		return drafts.release(chapter.id, id, viewer)
	}

	private fun act(user: OidcUser, code: String, id: UUID, block: (UUID, org.hack4impact.portal.auth.Viewer) -> Unit): DraftDetail {
		val (viewer, chapter) = access.manage(user, code)
		block(chapter.id, viewer)
		return drafts.detail(chapter.id, id, viewer)
	}
}
