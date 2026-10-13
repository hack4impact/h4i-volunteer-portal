package org.hack4impact.portal.drafts

import java.time.OffsetDateTime
import java.util.UUID

data class DraftSummary(
	val id: UUID,
	val name: String,
	/** draft, scheduled, released or discarded. */
	val status: String,
	val createdBy: String?,
	val createdAt: OffsetDateTime,
	val changes: Int,
	val scheduledFor: OffsetDateTime?,
	val releasedAt: OffsetDateTime?,
	val releasedBy: String?,
	/** Why a scheduled release went back to being a draft. */
	val releaseError: String?,
)

data class DraftChange(
	val id: UUID,
	val position: Int,
	/**
	 * create_project, update_project, set_status, add_member, set_member_role, remove_member, attach_resource, update_resource,
	 * detach_resource; create_rule, update_rule, set_rule_enabled, delete_rule (step 14).
	 */
	val operation: String,
	val summary: String,
	val projectId: UUID?,
	val createdBy: String?,
	val createdAt: OffsetDateTime,
	/** The rule a rule change is about. */
	val ruleId: UUID? = null,
)

/** A change that no longer works on live data (e.g. the person left the chapter): fix it before release. */
data class DraftConflict(val changeId: UUID, val position: Int, val summary: String, val problem: String)

/** One person's access as the draft would change it (PRD: preview per person and tool; the release summary email). */
data class PersonAccessChange(
	val personId: UUID,
	val name: String,
	val joins: List<String>,
	val leaves: List<String>,
	val gains: List<String>,
	val loses: List<String>,
	/** Gated resources they'd get once they sign the project agreement. */
	val afterAgreement: List<String>,
)

/** "Adds 34 people to 12 resources, removes 6" (PRD), with each person's changes. */
data class DraftPreview(
	val adds: Int,
	val removes: Int,
	val accessChanges: Int,
	val people: Int,
	val resources: Int,
	val afterAgreement: Int,
	val perPerson: List<PersonAccessChange>,
)

data class DraftDetail(
	val draft: DraftSummary,
	val changes: List<DraftChange>,
	val conflicts: List<DraftConflict>,
	val preview: DraftPreview,
	val canEdit: Boolean,
)

data class DraftInput(val name: String)

data class ScheduleInput(val at: OffsetDateTime)

/** What a release did; [conflicts] when it couldn't (nothing changed). */
data class ReleaseResult(val released: Boolean, val conflicts: List<DraftConflict>, val preview: DraftPreview?)
