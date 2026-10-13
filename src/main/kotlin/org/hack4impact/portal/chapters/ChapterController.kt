package org.hack4impact.portal.chapters

import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.auth.requireChapter
import org.hack4impact.portal.notion.NotionSettings
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Read-only chapter API. Every chapter endpoint checks the viewer's role in that chapter (PRD: chapter scoping). */
@RestController
@RequestMapping("/api")
class ChapterController(
	private val viewers: Viewers,
	private val chapters: ChapterQueries,
	private val notion: NotionSettings,
	private val homes: org.hack4impact.portal.members.MemberHomeQueries,
	@Value("\${portal.join-base-url}") private val joinBaseUrl: String,
) {
	@GetMapping("/me")
	fun me(@AuthenticationPrincipal user: OidcUser): Me {
		val viewer = viewers.of(user)
		return Me(viewer.email, viewer.name, viewer.personId, viewer.nationalAdmin, chapters.visibleTo(viewer))
	}

	@GetMapping("/chapters/{code}")
	fun chapter(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): ChapterOverview {
		val (viewer, chapter) = authorize(user, code)
		return ChapterOverview(
			chapter.id, chapter.code, chapter.name, chapter.status,
			kind = if (chapter.national) "national" else "chapter",
			role = viewer.chapterRoles[chapter.id] ?: "national",
			stats = chapters.stats(chapter.id, chapter.national),
			registrationLink = "${joinBaseUrl.trimEnd('/')}/${chapter.code}",
			notionProblems = notion.view(chapter.id, false).problems,
		)
	}

	@GetMapping("/chapters/{code}/members")
	fun members(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): List<MemberRow> {
		val (_, chapter) = authorize(user, code)
		return chapters.members(chapter.id)
	}

	/** Why a member has each of the chapter's resources (step 14; PRD: "each member's page shows why they have each resource"). */
	@GetMapping("/chapters/{code}/members/{personId}/access")
	fun memberAccess(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable personId: java.util.UUID): List<org.hack4impact.portal.members.MyAccess> {
		val (_, chapter) = authorize(user, code)
		if (!chapters.isMember(chapter.id, personId)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not a member of this chapter")
		return homes.access(personId, chapter.id, own = false)
	}

	@GetMapping("/chapters/{code}/sync")
	fun sync(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String): ChapterSync {
		val (_, chapter) = authorize(user, code)
		return chapters.sync(chapter.id)
	}

	/** 404 for an unknown chapter, 403 for one the viewer has no role in. */
	private fun authorize(user: OidcUser, code: String) =
		viewers.of(user).let { viewer ->
			val chapter = chapters.byCode(code) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No chapter $code")
			viewer.requireChapter(chapter.id)
			viewer to chapter
		}
}
