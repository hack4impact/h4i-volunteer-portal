package org.hack4impact.portal.rules

import org.hack4impact.portal.drafts.DraftPreview
import java.time.OffsetDateTime
import java.util.UUID

/** PRD: "When [who] → add to [projects] · [resources], with an access level and a tool filter". */
data class RuleInput(
	val name: String,
	val description: String? = null,
	val who: RuleWho = RuleWho(),
	val projects: RuleProjects = RuleProjects(),
	val resources: RuleResources = RuleResources(),
	/** Empty: every tool. slack, github, google, notion, vaultwarden. */
	val tools: List<String> = emptyList(),
	/** read, write or admin. */
	val access: String = "read",
	val enabled: Boolean = true,
)

/** Who it applies to: everyone, or anyone matching a chapter role, title, project role or being listed; in these statuses. */
data class RuleWho(
	/** active, alumni, removal_requested. Active only by default. */
	val statuses: List<String> = listOf("active"),
	val everyone: Boolean = false,
	/** lead, co_lead, viewer. */
	val chapterRoles: List<String> = emptyList(),
	val titles: List<String> = emptyList(),
	val projectRoles: List<UUID> = emptyList(),
	val people: List<UUID> = emptyList(),
)

/** Which projects' resources: none (the chapter's own resources), all live projects, listed ones, or tagged ones. */
data class RuleProjects(val scope: String = "none", val ids: List<UUID> = emptyList(), val tags: List<String> = emptyList())

/** Which resources: tagged ones or listed ones. */
data class RuleResources(val scope: String = "tags", val ids: List<UUID> = emptyList(), val tags: List<String> = emptyList())

/** How far a rule reaches right now (from the resolver). */
data class RuleReach(val people: Int, val resources: Int)

data class RuleView(
	val id: UUID,
	/** Null for a national default. */
	val chapterCode: String?,
	val name: String,
	val description: String?,
	val enabled: Boolean,
	val input: RuleInput,
	/** The rule in words: "Tech leads on any project → admin on resources tagged #eng · GitHub". */
	val sentence: String,
	val reach: RuleReach,
	val updatedBy: String?,
	val updatedAt: OffsetDateTime,
	/** Things that aren't wrong but are worth knowing (a tag that matches nothing yet). */
	val warnings: List<String>,
	/** Viewed in a draft: the draft's changes to this rule. */
	val pendingChanges: List<String> = emptyList(),
)

data class RuleOption(val id: UUID, val name: String, val detail: String? = null, val tool: String? = null)

/** What the editor offers to pick from. */
data class RuleOptions(
	val projectRoles: List<RuleOption>,
	val titles: List<String>,
	val projects: List<RuleOption>,
	val resources: List<RuleOption>,
	val tags: List<String>,
	val people: List<RuleOption>,
)

data class RulesPage(
	val rules: List<RuleView>,
	/** National defaults: they apply to everyone; leads see them read-only. */
	val national: List<RuleView>,
	val canEdit: Boolean,
	val options: RuleOptions,
)

/** A preview of a rule change before saving it ([ruleId] for an edit, [delete] to see what deleting would do). */
data class RulePreviewInput(val ruleId: UUID? = null, val input: RuleInput? = null, val delete: Boolean = false)

data class RulePreview(val sentence: String?, val preview: DraftPreview, val warnings: List<String>)

data class RuleEnabled(val enabled: Boolean)
