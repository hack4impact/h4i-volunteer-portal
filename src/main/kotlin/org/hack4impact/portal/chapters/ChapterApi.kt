package org.hack4impact.portal.chapters

import java.util.UUID

// Response shapes of the read-only chapter API (build plan step 5). The web app's TypeScript types are
// generated from the OpenAPI spec of these (web/openapi.json).

data class Me(
	val email: String,
	val name: String?,
	/** Null when the Google account isn't linked to anyone in the portal yet. */
	val personId: UUID?,
	val nationalAdmin: Boolean,
	/** Chapters this person may see. Empty means signed in but no access. */
	val chapters: List<ChapterRef>,
)

/** [role] is the viewer's role here: lead, co_lead, viewer, or national for national admins. */
data class ChapterRef(val id: UUID, val code: String, val name: String, val role: String)

data class ChapterOverview(
	val id: UUID,
	val code: String,
	val name: String,
	val status: String,
	val role: String,
	val stats: ChapterStats,
	val registrationLink: String,
	/** Notion route pages the integration couldn't reach at the last check (PRD: flagged on the dashboard). */
	val notionProblems: Int,
)

data class ChapterStats(val activeMembers: Int, val alumni: Int, val liveProjects: Int, val leads: Int)

data class MemberRow(
	val personId: UUID,
	val name: String,
	/** The @hack4impact.org address, else the school address. Personal emails aren't shown (PRD: Security). */
	val email: String?,
	/** applicant, active, alumni, removal_requested or removed. */
	val status: String,
	val kind: String,
	/** lead, co_lead or viewer in this chapter, if any. */
	val chapterRole: String?,
	/** Official title, e.g. "Director of product" (decision 35). */
	val title: String?,
	/** Live projects in this chapter. */
	val projects: List<String>,
	val accounts: List<AccountSummary>,
	/** Whether the person has confirmed their details (claimed or registered). */
	val claimed: Boolean,
)

/** One tool account: [state] is unverified, invited, accepted, confirmed, suspended or removed. */
data class AccountSummary(val tool: String, val state: String)

/** The latest sync run per tool, limited to this chapter's resources (build plan step 6: dry runs only). */
data class ChapterSync(val tools: List<ToolSync>, val changes: List<PlannedChange>)

data class ToolSync(
	val tool: String,
	/** completed, paused (a real run would stop: blast radius or kill switch), or failed. */
	val status: String,
	val mode: String,
	val ranAt: java.time.OffsetDateTime,
	val error: String?,
	val adds: Int,
	val changes: Int,
	val removals: Int,
	val drift: Int,
	val unmatchedAccounts: Int,
	val missingResources: Int,
	val creates: Int,
)

/** [kind] is add, change, remove, drift, unmatched_account, missing_resource or create_resource. */
data class PlannedChange(
	val kind: String,
	val tool: String,
	val resource: String,
	val person: String?,
	val accountId: String?,
	val fromAccess: String?,
	val toAccess: String?,
)
