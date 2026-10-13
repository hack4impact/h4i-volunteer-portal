package org.hack4impact.portal.rules

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.auth.Viewers
import org.hack4impact.portal.chapters.ChapterAccess
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
import java.util.UUID

/**
 * A chapter's automation rules (build plan step 14). Everyone with a chapter role reads them, with the national defaults;
 * leads, co-leads and national change them. Every change answers with the page, so the list shows the new reach. With
 * `?draft=<id>` reads show the draft's version and changes go into the draft (step 12).
 */
@RestController
@RequestMapping("/api/chapters/{code}/rules")
class RuleController(private val access: ChapterAccess, private val rules: Rules, private val drafts: Drafts, private val syncs: SyncRequests) {
	@GetMapping
	fun page(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestParam(required = false) draft: UUID?): RulesPage {
		if (draft == null) {
			val (viewer, chapter) = access.read(user, code)
			return rules.page(chapter.id, viewer.canManage(chapter.id))
		}
		val (viewer, chapter) = access.manage(user, code)
		return inDraft(chapter.id, draft, viewer)
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestBody input: RuleInput, @RequestParam(required = false) draft: UUID?) =
		change(user, code, draft, { drafts.operation("create_rule", UUID.randomUUID(), "input" to input) }) { chapter, viewer -> rules.create(chapter, input, viewer) }

	@PutMapping("/{id}")
	fun update(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID, @RequestBody input: RuleInput, @RequestParam(required = false) draft: UUID?,
	) = change(user, code, draft, { drafts.operation("update_rule", id, "input" to input) }) { chapter, viewer -> rules.update(chapter, id, input, viewer) }

	@PostMapping("/{id}/enabled")
	fun setEnabled(
		@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID, @RequestBody input: RuleEnabled, @RequestParam(required = false) draft: UUID?,
	) = change(user, code, draft, { drafts.operation("set_rule_enabled", id, "enabled" to input.enabled) }) { chapter, viewer -> rules.setEnabled(chapter, id, input.enabled, viewer) }

	@DeleteMapping("/{id}")
	fun delete(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @PathVariable id: UUID, @RequestParam(required = false) draft: UUID?) =
		change(user, code, draft, { drafts.operation("delete_rule", id) }) { chapter, viewer -> rules.delete(chapter, id, viewer) }

	/** What a change would do, saving nothing; in a draft, on top of the draft's changes. */
	@PostMapping("/preview")
	fun preview(@AuthenticationPrincipal user: OidcUser, @PathVariable code: String, @RequestBody input: RulePreviewInput, @RequestParam(required = false) draft: UUID?): RulePreview {
		val (viewer, chapter) = access.manage(user, code)
		if (draft != null) return drafts.inDraft(chapter.id, draft, viewer) { rules.preview(chapter.id, input, viewer) }
		return rules.preview(chapter.id, input, viewer)
	}

	private fun inDraft(chapterId: UUID, draft: UUID, viewer: Viewer): RulesPage {
		val pending = drafts.pendingByTarget(draft)
		val page = drafts.inDraft(chapterId, draft, viewer) { rules.page(chapterId, true) }
		return page.copy(rules = page.rules.map { it.copy(pendingChanges = pending[it.id].orEmpty()) })
	}

	/** Applies now and asks for a sync (the outbox, after commit), or records the change in [draft]. */
	private fun change(user: OidcUser, code: String, draft: UUID?, operation: () -> Drafts.Operation, apply: (UUID, Viewer) -> Unit): RulesPage {
		val (viewer, chapter) = access.manage(user, code)
		if (draft != null) {
			drafts.record(chapter.id, draft, viewer, null) { operation() }
			return inDraft(chapter.id, draft, viewer)
		}
		rules.inTransaction { apply(chapter.id, viewer); syncs.request("rule") }
		return rules.page(chapter.id, true)
	}
}

/** National defaults: rules for every chapter, edited by national admins only (decision 51). */
@RestController
@RequestMapping("/api/national/rules")
class NationalRuleController(private val viewers: Viewers, private val rules: Rules, private val syncs: SyncRequests) {
	private fun national(user: OidcUser): Viewer = viewers.of(user).also { if (!it.nationalAdmin) throw AccessDeniedException("National admins only") }

	@GetMapping
	fun page(@AuthenticationPrincipal user: OidcUser): RulesPage = national(user).let { rules.page(null, true) }

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	fun create(@AuthenticationPrincipal user: OidcUser, @RequestBody input: RuleInput): RulesPage = change(user) { rules.create(null, input, it) }

	@PutMapping("/{id}")
	fun update(@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID, @RequestBody input: RuleInput): RulesPage = change(user) { rules.update(null, id, input, it) }

	@PostMapping("/{id}/enabled")
	fun setEnabled(@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID, @RequestBody input: RuleEnabled): RulesPage =
		change(user) { rules.setEnabled(null, id, input.enabled, it) }

	@DeleteMapping("/{id}")
	fun delete(@AuthenticationPrincipal user: OidcUser, @PathVariable id: UUID): RulesPage = change(user) { rules.delete(null, id, it) }

	@PostMapping("/preview")
	fun preview(@AuthenticationPrincipal user: OidcUser, @RequestBody input: RulePreviewInput): RulePreview = rules.preview(null, input, national(user))

	private fun change(user: OidcUser, apply: (Viewer) -> Unit): RulesPage {
		val viewer = national(user)
		rules.inTransaction { apply(viewer); syncs.request("rule") }
		return rules.page(null, true)
	}
}
