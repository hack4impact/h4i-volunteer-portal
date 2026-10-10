package org.hack4impact.portal.projects

import org.hack4impact.portal.chapters.PlannedChange
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// Projects API (build plan step 8). Reading needs a role in the chapter; changing needs lead, co-lead or national.

data class ProjectSummary(
	val id: UUID,
	val name: String,
	val slug: String,
	/** draft, active, paused or closed. Only active and paused projects give access. */
	val status: String,
	val type: String?,
	val tags: List<String>,
	val term: String?,
	val partner: String?,
	val members: Int,
	val resources: Int,
	val updatedAt: OffsetDateTime,
)

data class ProjectDetail(
	val id: UUID,
	val name: String,
	val slug: String,
	val status: String,
	val type: String?,
	val tags: List<String>,
	val termId: UUID?,
	val term: String?,
	val partner: String?,
	val startsOn: LocalDate?,
	val endsOn: LocalDate?,
	val members: List<ProjectMemberRow>,
	val resources: List<ProjectResourceRow>,
	/** Members who signed (or were waived from) the project agreement, out of all members. */
	val agreementsSigned: Int,
	val notion: NotionPlan,
	/** Who would get access; for a draft project, as if it were active. */
	val preview: AccessPreview,
	/** The latest dry run's planned changes for this project's resources. */
	val planned: List<PlannedChange>,
	val lastDryRunAt: OffsetDateTime?,
	/** The project changed after the latest dry run, so [planned] may be out of date. */
	val plannedOutOfDate: Boolean,
	val activity: List<ActivityRow>,
	val canEdit: Boolean,
)

data class ProjectMemberRow(
	val personId: UUID,
	val name: String,
	val email: String?,
	/** The person's status in H4I: active, alumni, … */
	val status: String,
	val roleId: UUID?,
	val role: String?,
	val isLead: Boolean,
	/** pending, signed or waived. */
	val agreementStatus: String,
)

data class ProjectResourceRow(
	val resourceId: UUID,
	val tool: String,
	val name: String,
	/** to_create (only in the portal so far), exists (linked to the tool) or archived. */
	val state: String,
	/** team, role or leads (project leads). */
	val audience: String,
	val roleId: UUID?,
	val role: String?,
	/** read, write or admin. */
	val access: String,
	val requiresAgreement: Boolean,
	val tags: List<String>,
	/** Other projects using the same resource. */
	val sharedWith: List<String>,
	/** The adoption scan found something with this name in the tool already. */
	val existsInTool: Boolean,
	/** People who would have it (from the resolver). */
	val getting: Int,
	/** People waiting on the project agreement for it. */
	val awaitingAgreement: Int,
)

/** [status]: has_page, planned, no_route (no route and no default) or not_set_up (no Notion settings). */
data class NotionPlan(val status: String, val title: String, val pageId: String?, val parentPageId: String?, val parentPath: String?, val matchedBy: String?)

data class AccessPreview(val people: Int, val grants: Int, val awaitingAgreement: Int, val asIfActive: Boolean)

data class ActivityRow(val at: OffsetDateTime, val actor: String?, val action: String, val detail: String?)

data class ProjectRoleOption(val id: UUID, val name: String, val isLead: Boolean)

data class TermOption(val id: UUID, val label: String)

data class ChapterResourceOption(val id: UUID, val tool: String, val name: String, val state: String, val projects: List<String>)

data class ProjectInput(
	val name: String,
	/** Only on create; derived from the name when blank. With the chapter code it names the standard resources. */
	val slug: String? = null,
	val type: String? = null,
	val tags: List<String> = emptyList(),
	val termId: UUID? = null,
	val partner: String? = null,
	val startsOn: LocalDate? = null,
	val endsOn: LocalDate? = null,
)

data class StatusChange(val status: String)

data class MemberInput(val personId: UUID, val roleId: UUID? = null)

data class MemberRoleInput(val roleId: UUID? = null)

/** Attach an existing resource ([resourceId]) or a new one ([tool] and [name]) to the project. */
data class ResourceInput(
	val resourceId: UUID? = null,
	val tool: String? = null,
	val name: String? = null,
	val audience: String = "team",
	val roleId: UUID? = null,
	val access: String = "write",
	val requiresAgreement: Boolean = false,
	val tags: List<String> = emptyList(),
)
