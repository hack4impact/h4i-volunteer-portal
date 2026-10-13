package org.hack4impact.portal.drafts

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.records.ChangeRecord
import org.hack4impact.portal.db.tables.records.ChangesetRecord
import org.hack4impact.portal.db.tables.records.ProjectRecord
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHANGE
import org.hack4impact.portal.db.tables.references.CHANGESET
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.projects.MemberInput
import org.hack4impact.portal.projects.MemberRoleInput
import org.hack4impact.portal.projects.ProjectInput
import org.hack4impact.portal.projects.ProjectQueries
import org.hack4impact.portal.projects.ProjectService
import org.hack4impact.portal.projects.ResourceInput
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.rules.RuleInput
import org.hack4impact.portal.rules.Rules
import org.hack4impact.portal.sync.SyncRequests
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** A chapter's draft was released; members whose access changed get one summary email ([DraftEmails]). */
data class DraftReleased(val changesetId: UUID, val chapter: String, val draft: String, val people: List<PersonAccessChange>)

/**
 * Drafts (build plan step 12; PRD drafts and releases). A draft is an ordered list of project operations. They're never
 * applied while it's a draft: showing it, previewing it and checking a new change all replay them in a transaction
 * that's rolled back, each in its own savepoint, so one that no longer works on live data becomes a conflict instead
 * of failing the rest. Releasing replays them for real, in one transaction with the sync request (outbox).
 */
@Component
class Drafts(
	private val dsl: DSLContext,
	private val projects: ProjectService,
	private val rules: Rules,
	private val diffs: AccessDiff,
	private val transactions: PlatformTransactionManager,
	private val mapper: JsonMapper,
	private val syncs: SyncRequests,
	private val events: ApplicationEventPublisher,
) {
	/**
	 * A project or rule operation as a draft records it. [targetId] is the project or rule; it's chosen up front for one
	 * the draft creates.
	 */
	data class Operation(val op: String, val targetId: UUID, val payload: JsonNode) {
		val targetType get() = if (op.endsWith("_rule") || op == "set_rule_enabled") "rule" else "project"
	}

	companion object {
		private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)
		private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
		private fun bad(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
	}

	fun operation(op: String, targetId: UUID, vararg payload: Pair<String, Any?>) = Operation(op, targetId, mapper.valueToTree(mapOf(*payload)))

	// ---- Drafts -------------------------------------------------------------------------------------------------------

	fun create(chapterId: UUID, name: String, viewer: Viewer): DraftSummary {
		val n = name.trim().ifEmpty { bad("Give the draft a name, e.g. “Fall 2026 teams”") }
		val id = dsl.insertInto(CHANGESET).set(CHANGESET.CHAPTER_ID, chapterId).set(CHANGESET.NAME, n).set(CHANGESET.CREATED_BY, viewer.personId ?: bad("Only a person can make a draft"))
			.returningResult(CHANGESET.ID).fetchSingle().value1()!!
		audit(viewer, chapterId, id, "draft.create", "Started the draft “$n”")
		return summaries(chapterId, id).single()
	}

	/** Open drafts, and those released or discarded in the last 60 days. */
	fun list(chapterId: UUID): List<DraftSummary> = summaries(chapterId, null)

	private fun summaries(chapterId: UUID, only: UUID?): List<DraftSummary> {
		val creator = PERSON.`as`("creator")
		val releaser = PERSON.`as`("releaser")
		val counts = DSL.select(DSL.count()).from(CHANGE).where(CHANGE.CHANGESET_ID.eq(CHANGESET.ID)).asField<Int>("changes")
		return dsl.select(CHANGESET.asterisk(), creator.FIRST_NAME, creator.LAST_NAME, releaser.FIRST_NAME, releaser.LAST_NAME, counts).from(CHANGESET)
			.leftJoin(creator).on(creator.ID.eq(CHANGESET.CREATED_BY)).leftJoin(releaser).on(releaser.ID.eq(CHANGESET.RELEASED_BY))
			.where(
				CHANGESET.CHAPTER_ID.eq(chapterId),
				if (only != null) CHANGESET.ID.eq(only)
				else CHANGESET.STATUS.`in`("draft", "scheduled").or(DSL.coalesce(CHANGESET.RELEASED_AT, CHANGESET.DISCARDED_AT).gt(OffsetDateTime.now().minusDays(60))),
			)
			.orderBy(CHANGESET.CREATED_AT.desc())
			.fetch { r ->
				val c = r.into(CHANGESET)
				DraftSummary(
					c.id!!, c.name!!, c.status!!, r.get(creator.FIRST_NAME)?.let { "$it ${r.get(creator.LAST_NAME)}" }, c.createdAt!!, r.get(counts) ?: 0,
					c.scheduledFor, c.releasedAt, r.get(releaser.FIRST_NAME)?.let { "$it ${r.get(releaser.LAST_NAME)}" }, c.releaseError,
				)
			}
	}

	fun changeset(chapterId: UUID, id: UUID): ChangesetRecord =
		dsl.selectFrom(CHANGESET).where(CHANGESET.ID.eq(id), CHANGESET.CHAPTER_ID.eq(chapterId)).fetchOne() ?: notFound("No such draft")

	/** The draft with its changes, conflicts and access preview. */
	fun detail(chapterId: UUID, id: UUID, viewer: Viewer): DraftDetail {
		val cs = changeset(chapterId, id)
		val changes = changes(id)
		val (conflicts, preview) = if (cs.status in setOf("draft", "scheduled")) {
			rolledBack { s ->
				val before = diffs.snapshot()
				val conflicts = replay(chapterId, id, viewer, s)
				conflicts to diffs.diff(before, diffs.snapshot())
			}
		} else {
			emptyList<DraftConflict>() to (cs.releasedSummary?.let { mapper.readValue(it.data(), DraftPreview::class.java) } ?: DraftPreview(0, 0, 0, 0, 0, 0, emptyList()))
		}
		return DraftDetail(summaries(chapterId, id).single(), changes, conflicts, preview, viewer.canManage(chapterId) && cs.status == "draft")
	}

	private fun changes(id: UUID): List<DraftChange> = dsl.select(CHANGE.asterisk(), PERSON.FIRST_NAME, PERSON.LAST_NAME).from(CHANGE).leftJoin(PERSON).on(PERSON.ID.eq(CHANGE.CREATED_BY))
		.where(CHANGE.CHANGESET_ID.eq(id)).orderBy(CHANGE.POSITION)
		.fetch { r ->
			val c = r.into(CHANGE)
			val rule = c.targetType == "rule"
			DraftChange(c.id!!, c.position!!, c.operation!!, c.summary!!, c.targetId.takeUnless { rule }, r.get(PERSON.FIRST_NAME)?.let { "$it ${r.get(PERSON.LAST_NAME)}" }, c.createdAt!!,
				c.targetId.takeIf { rule })
		}

	/** Pending change summaries per project or rule, for marking them on the chapter's screens. */
	fun pendingByTarget(id: UUID): Map<UUID, List<String>> = dsl.select(CHANGE.TARGET_ID, CHANGE.SUMMARY).from(CHANGE).where(CHANGE.CHANGESET_ID.eq(id))
		.orderBy(CHANGE.POSITION).fetch().filter { it.value1() != null }.groupBy({ it.value1()!! }, { it.value2()!! })

	// ---- Recording changes --------------------------------------------------------------------------------------------

	/**
	 * Checks [build]'s operation against the draft as it stands (it must work there, or the lead hears why at once) and
	 * records it. [slug] finds the project as the draft has it, also one the draft created.
	 */
	fun record(chapterId: UUID, id: UUID, viewer: Viewer, slug: String?, build: (ProjectRecord?) -> Operation): Operation {
		editable(chapterId, id)
		val (operation, summary) = rolledBack { s ->
			replay(chapterId, id, viewer, s)
			val project = slug?.let { bySlug(chapterId, it) ?: notFound("No project $it") }
			if (project?.status == "closed") conflict("This project is closed")
			val op = build(project)
			apply(chapterId, op, viewer)
			op to describe(op)
		}
		TransactionTemplate(transactions).executeWithoutResult {
			editable(chapterId, id, lock = true)
			val next = (dsl.select(DSL.max(CHANGE.POSITION)).from(CHANGE).where(CHANGE.CHANGESET_ID.eq(id)).fetchOne()?.value1() ?: 0) + 1
			dsl.insertInto(CHANGE).set(CHANGE.CHANGESET_ID, id).set(CHANGE.POSITION, next).set(CHANGE.OPERATION, operation.op)
				.set(CHANGE.TARGET_TYPE, operation.targetType).set(CHANGE.TARGET_ID, operation.targetId).set(CHANGE.PAYLOAD, JSONB.valueOf(mapper.writeValueAsString(operation.payload)))
				.set(CHANGE.CREATED_BY, viewer.personId).set(CHANGE.SUMMARY, summary).execute()
			dsl.update(CHANGESET).set(CHANGESET.UPDATED_AT, OffsetDateTime.now()).where(CHANGESET.ID.eq(id)).execute()
		}
		return operation
	}

	/** Runs [block] against the chapter as the draft would leave it; nothing is kept. */
	fun <T> inDraft(chapterId: UUID, id: UUID, viewer: Viewer, block: () -> T): T {
		// A released or discarded draft is history: replaying it on live data would show its changes twice (review L13).
		val cs = changeset(chapterId, id)
		if (cs.status !in setOf("draft", "scheduled")) throw ResponseStatusException(HttpStatus.GONE, "This draft was already ${cs.status}")
		return rolledBack { s ->
			replay(chapterId, id, viewer, s)
			block()
		}
	}

	fun bySlug(chapterId: UUID, slug: String): ProjectRecord? = dsl.selectFrom(PROJECT).where(PROJECT.CHAPTER_ID.eq(chapterId), PROJECT.SLUG.eq(slug)).fetchOne()

	fun removeChange(chapterId: UUID, id: UUID, changeId: UUID, viewer: Viewer) {
		editable(chapterId, id)
		val deleted = dsl.deleteFrom(CHANGE).where(CHANGE.ID.eq(changeId), CHANGE.CHANGESET_ID.eq(id)).execute()
		if (deleted == 0) notFound("Not in this draft")
	}

	fun rename(chapterId: UUID, id: UUID, name: String, viewer: Viewer) {
		editable(chapterId, id)
		dsl.update(CHANGESET).set(CHANGESET.NAME, name.trim().ifEmpty { bad("Give the draft a name") }).where(CHANGESET.ID.eq(id)).execute()
	}

	/** PRD: a discarded draft changes nothing. */
	fun discard(chapterId: UUID, id: UUID, viewer: Viewer) {
		val cs = changeset(chapterId, id)
		if (cs.status !in setOf("draft", "scheduled")) conflict("This draft was already ${cs.status}")
		dsl.update(CHANGESET).set(CHANGESET.STATUS, "discarded").set(CHANGESET.DISCARDED_AT, OffsetDateTime.now()).setNull(CHANGESET.SCHEDULED_FOR)
			.where(CHANGESET.ID.eq(id)).execute()
		audit(viewer, chapterId, id, "draft.discard", "Discarded the draft “${cs.name}”")
	}

	/** PRD: releases can be scheduled. Only a draft without conflicts; while scheduled it can't change (unschedule first). */
	fun schedule(chapterId: UUID, id: UUID, at: OffsetDateTime, viewer: Viewer) {
		editable(chapterId, id)
		if (!at.isAfter(OffsetDateTime.now().plusMinutes(1))) bad("Pick a time in the future")
		val detail = detail(chapterId, id, viewer)
		if (detail.changes.isEmpty()) bad("The draft has no changes")
		detail.conflicts.firstOrNull()?.let { conflict("Fix the conflicts first: ${it.summary}: ${it.problem}") }
		dsl.update(CHANGESET).set(CHANGESET.STATUS, "scheduled").set(CHANGESET.SCHEDULED_FOR, at).set(CHANGESET.SCHEDULED_BY, viewer.personId).setNull(CHANGESET.RELEASE_ERROR)
			.where(CHANGESET.ID.eq(id)).execute()
		audit(viewer, chapterId, id, "draft.schedule", "Scheduled “${detail.draft.name}” for $at")
	}

	fun unschedule(chapterId: UUID, id: UUID, viewer: Viewer) {
		val cs = changeset(chapterId, id)
		if (cs.status != "scheduled") conflict("It isn't scheduled")
		dsl.update(CHANGESET).set(CHANGESET.STATUS, "draft").setNull(CHANGESET.SCHEDULED_FOR).where(CHANGESET.ID.eq(id)).execute()
		audit(viewer, chapterId, id, "draft.unschedule", "Took “${cs.name}” off the schedule")
	}

	private fun editable(chapterId: UUID, id: UUID, lock: Boolean = false): ChangesetRecord {
		val query = dsl.selectFrom(CHANGESET).where(CHANGESET.ID.eq(id), CHANGESET.CHAPTER_ID.eq(chapterId))
		val cs = (if (lock) query.forUpdate() else query).fetchOne() ?: notFound("No such draft")
		when (cs.status) {
			"draft" -> return cs
			"scheduled" -> conflict("This draft is scheduled. Take it off the schedule to change it.")
			else -> conflict("This draft was already ${cs.status}")
		}
	}

	// ---- Release ------------------------------------------------------------------------------------------------------

	/**
	 * PRD: release applies every change in one database transaction, then queues syncs for everyone affected (through
	 * the outbox, in the same transaction); members get one summary each. Any conflict releases nothing.
	 */
	fun release(chapterId: UUID, id: UUID, viewer: Viewer): ReleaseResult = TransactionTemplate(transactions).execute { s ->
		val cs = dsl.selectFrom(CHANGESET).where(CHANGESET.ID.eq(id), CHANGESET.CHAPTER_ID.eq(chapterId)).forUpdate().fetchOne() ?: notFound("No such draft")
		if (cs.status !in setOf("draft", "scheduled")) conflict("This draft was already ${cs.status}")
		if (!dsl.fetchExists(CHANGE, CHANGE.CHANGESET_ID.eq(id))) bad("The draft has no changes")
		val before = diffs.snapshot()
		val conflicts = replay(chapterId, id, viewer, s)
		if (conflicts.isNotEmpty()) {
			s.setRollbackOnly()
			return@execute ReleaseResult(false, conflicts, null)
		}
		val preview = diffs.diff(before, diffs.snapshot())
		dsl.update(CHANGESET).set(CHANGESET.STATUS, "released").set(CHANGESET.RELEASED_AT, OffsetDateTime.now()).set(CHANGESET.RELEASED_BY, viewer.personId)
			.setNull(CHANGESET.RELEASE_ERROR).set(CHANGESET.RELEASED_SUMMARY, JSONB.valueOf(mapper.writeValueAsString(preview)))
			.where(CHANGESET.ID.eq(id)).execute()
		audit(viewer, chapterId, id, "draft.release", "Released “${cs.name}”: ${counts(preview)}")
		syncs.request("release")
		val chapter = dsl.select(CHAPTER.NAME).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle().value1()!!
		if (preview.perPerson.isNotEmpty()) events.publishEvent(DraftReleased(id, chapter, cs.name!!, preview.perPerson))
		ReleaseResult(true, emptyList(), preview)
	}

	/** Releases scheduled drafts that are due; one with conflicts goes back to being a draft, and whoever scheduled it hears why. */
	fun releaseDue(now: OffsetDateTime = OffsetDateTime.now()): List<Pair<UUID, ReleaseResult>> {
		val due = dsl.selectFrom(CHANGESET).where(CHANGESET.STATUS.eq("scheduled"), CHANGESET.SCHEDULED_FOR.le(now)).fetch()
		return due.map { cs ->
			val by = cs.scheduledBy ?: cs.createdBy!!
			val who = dsl.selectFrom(PERSON).where(PERSON.ID.eq(by)).fetchSingle()
			// Released with the permissions they have now, not when they scheduled it (review M5).
			val national = dsl.fetchExists(NATIONAL_ADMIN, NATIONAL_ADMIN.PERSON_ID.eq(by))
			val leads = dsl.fetchExists(CHAPTER_ROLE, CHAPTER_ROLE.PERSON_ID.eq(by), CHAPTER_ROLE.CHAPTER_ID.eq(cs.chapterId), CHAPTER_ROLE.ROLE.`in`("lead", "co_lead"), CHAPTER_ROLE.ENDS_AT.isNull)
			val allowed = who.deletedAt == null && who.status !in setOf("removed", "applicant") && (national || leads)
			val viewer = Viewer(by, who.orgEmail ?: "", "${who.firstName} ${who.lastName}", national, if (leads) mapOf(cs.chapterId!! to "lead") else emptyMap())
			val result = if (!allowed) {
				ReleaseResult(false, listOf(DraftConflict(cs.id!!, 0, cs.name!!, "${who.firstName} ${who.lastName}, who scheduled it, no longer leads the chapter: a lead has to schedule it again")), null)
			} else try { release(cs.chapterId!!, cs.id!!, viewer) } catch (e: ResponseStatusException) {
				ReleaseResult(false, listOf(DraftConflict(cs.id!!, 0, cs.name!!, e.reason ?: "couldn't release")), null)
			}
			if (!result.released) {
				val why = result.conflicts.joinToString("; ") { "${it.summary}: ${it.problem}" }.take(1000)
				// In a transaction, so the email (sent after commit, through the outbox) goes with the change.
				TransactionTemplate(transactions).executeWithoutResult {
					dsl.update(CHANGESET).set(CHANGESET.STATUS, "draft").setNull(CHANGESET.SCHEDULED_FOR).set(CHANGESET.RELEASE_ERROR, why)
						.where(CHANGESET.ID.eq(cs.id), CHANGESET.STATUS.eq("scheduled")).execute()
					events.publishEvent(ScheduledReleaseFailed(cs.id!!, by, cs.name!!, why))
				}
			}
			cs.id!! to result
		}
	}

	// ---- Replay ---------------------------------------------------------------------------------------------------------

	private fun <T> rolledBack(block: (TransactionStatus) -> T): T {
		var result: Result<T>? = null
		TransactionTemplate(transactions).executeWithoutResult { s ->
			result = runCatching { block(s) }
			s.setRollbackOnly()
		}
		return result!!.getOrThrow()
	}

	/** Applies the draft's changes in order, each in a savepoint; those that fail are the draft's conflicts. */
	private fun replay(chapterId: UUID, id: UUID, viewer: Viewer, s: TransactionStatus): List<DraftConflict> {
		val conflicts = mutableListOf<DraftConflict>()
		for (c in dsl.selectFrom(CHANGE).where(CHANGE.CHANGESET_ID.eq(id)).orderBy(CHANGE.POSITION).fetch()) {
			val savepoint = s.createSavepoint()
			try {
				apply(chapterId, read(c), viewer)
				s.releaseSavepoint(savepoint)
			} catch (e: RuntimeException) {
				s.rollbackToSavepoint(savepoint)
				conflicts += DraftConflict(c.id!!, c.position!!, c.summary!!, problem(e))
			}
		}
		return conflicts
	}

	private fun problem(e: RuntimeException) = when (e) {
		is ResponseStatusException -> e.reason ?: e.statusCode.toString()
		is DataAccessException -> "the database refused it (${e.mostSpecificCause.message?.take(200)})"
		else -> e.message ?: e.javaClass.simpleName
	}

	private fun read(c: ChangeRecord) = Operation(c.operation!!, c.targetId!!, mapper.readTree(c.payload!!.data()))

	private fun <T> input(op: Operation, type: Class<T>): T = mapper.treeToValue(op.payload.path("input"), type)

	private fun uuid(op: Operation, field: String): UUID = UUID.fromString(op.payload.path(field).asString())

	/** The project and rule operations, as their APIs do them. */
	private fun apply(chapterId: UUID, op: Operation, viewer: Viewer) {
		fun project(): ProjectRecord = dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(op.targetId), PROJECT.CHAPTER_ID.eq(chapterId)).fetchOne()
			?: conflict("The project isn't there any more")
		fun open(): ProjectRecord = project().also { if (it.status == "closed") conflict("${it.name} was closed") }
		when (op.op) {
			"create_project" -> projects.create(chapterId, input(op, ProjectInput::class.java), viewer, id = op.targetId)
			"update_project" -> projects.update(chapterId, open(), input(op, ProjectInput::class.java), viewer)
			"set_status" -> projects.setStatus(chapterId, open(), op.payload.path("status").asString(), viewer)
			"add_member" -> projects.addMember(chapterId, open(), input(op, MemberInput::class.java), viewer)
			"set_member_role" -> projects.setMemberRole(chapterId, open(), uuid(op, "personId"), input(op, MemberRoleInput::class.java), viewer)
			"remove_member" -> projects.removeMember(chapterId, open(), uuid(op, "personId"), viewer)
			"attach_resource" -> projects.attachResource(chapterId, open(), input(op, ResourceInput::class.java), viewer,
				newResourceId = op.payload.path("newResourceId").takeIf { it.isString }?.let { UUID.fromString(it.asString()) })
			"update_resource" -> projects.updateResource(chapterId, open(), uuid(op, "resourceId"), input(op, ResourceInput::class.java), viewer)
			"detach_resource" -> projects.detachResource(chapterId, open(), uuid(op, "resourceId"), viewer)
			"create_rule" -> rules.create(chapterId, input(op, RuleInput::class.java), viewer, id = op.targetId)
			"update_rule" -> rules.update(chapterId, op.targetId, input(op, RuleInput::class.java), viewer)
			"set_rule_enabled" -> rules.setEnabled(chapterId, op.targetId, op.payload.path("enabled").asBoolean(), viewer)
			"delete_rule" -> rules.delete(chapterId, op.targetId, viewer)
			else -> bad("Unknown change ${op.op}")
		}
	}

	/** The change in words, written right after it was applied (so new projects and resources have names). */
	private fun describe(op: Operation): String {
		val project = dsl.select(PROJECT.NAME).from(PROJECT).where(PROJECT.ID.eq(op.targetId)).fetchOne()?.value1() ?: "the project"
		fun person(id: UUID) = dsl.select(PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME).from(PERSON).where(PERSON.ID.eq(id)).fetchOne()
			?.let { ProjectQueries.name(it.value3(), it.value1()!!, it.value2()!!) } ?: "someone"
		fun role(id: UUID?) = id?.let { dsl.select(PROJECT_ROLE.NAME).from(PROJECT_ROLE).where(PROJECT_ROLE.ID.eq(it)).fetchOne()?.value1() }
		fun resource(id: UUID) = dsl.select(RESOURCE.TOOL, RESOURCE.NAME).from(RESOURCE).where(RESOURCE.ID.eq(id)).fetchOne()?.let { diffs.label(it.value1()!!, it.value2()!!) } ?: "a resource"
		val rule = "“${rules.name(op.targetId)}”"
		return when (op.op) {
			"create_project" -> "Create $project"
			"update_project" -> "Edit $project's details"
			"set_status" -> when (val to = op.payload.path("status").asString()) {
				"active" -> "Start $project"
				"paused" -> "Pause $project"
				"closed" -> "Close $project"
				else -> "Make $project $to"
			}
			"add_member" -> input(op, MemberInput::class.java).let { m -> "Add ${person(m.personId)} to $project" + (role(m.roleId)?.let { " as $it" } ?: "") }
			"set_member_role" -> "Make ${person(uuid(op, "personId"))} ${role(input(op, MemberRoleInput::class.java).roleId) ?: "a member without a role"} on $project"
			"remove_member" -> "Remove ${person(uuid(op, "personId"))} from $project"
			"attach_resource" -> {
				val i = input(op, ResourceInput::class.java)
				val id = i.resourceId ?: op.payload.path("newResourceId").takeIf { it.isString }?.let { UUID.fromString(it.asString()) }
				"Attach ${id?.let(::resource) ?: "${i.name} (${i.tool})"} to $project"
			}
			"update_resource" -> "Change who gets ${resource(uuid(op, "resourceId"))} on $project"
			"detach_resource" -> "Detach ${resource(uuid(op, "resourceId"))} from $project"
			"create_rule" -> "Add the rule $rule"
			"update_rule" -> "Edit the rule $rule"
			"set_rule_enabled" -> if (op.payload.path("enabled").asBoolean()) "Turn on the rule $rule" else "Turn off the rule $rule"
			"delete_rule" -> "Delete the rule $rule"
			else -> op.op
		}
	}

	private fun counts(p: DraftPreview) = "adds ${p.people} people to ${p.resources} resources (${p.adds} grants), removes ${p.removes}"

	private fun audit(viewer: Viewer, chapterId: UUID, id: UUID, action: String, summary: String) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "changeset").set(AUDIT_EVENT.TARGET_ID, id)
			.set(AUDIT_EVENT.CHAPTER_ID, chapterId).set(AUDIT_EVENT.AFTER, JSONB.valueOf(mapper.writeValueAsString(mapOf("summary" to summary))))
			.execute()
	}
}

/** A scheduled release found conflicts and went back to being a draft. */
data class ScheduledReleaseFailed(val changesetId: UUID, val scheduledBy: UUID, val draft: String, val why: String)
