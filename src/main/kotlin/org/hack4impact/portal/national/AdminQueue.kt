package org.hack4impact.portal.national

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The national admin queue (PRD): changes with no API, written as actions for a person to make, and runs held at
 * the blast-radius limit. Marking a member task done is the record that it was applied (Notion's state can't be
 * read); tasks that are no longer wanted cancel themselves at the next run.
 */
@Component
class AdminQueue(private val dsl: DSLContext) {
	companion object {
		/** A confirmation of held removals lets runs within this window make that many removals. */
		val CONFIRMATION_WINDOW: Duration = Duration.ofHours(24)
	}

	fun tasks(): List<QueueTask> =
		dsl.select(ADMIN_TASK.ID, ADMIN_TASK.TOOL, ADMIN_TASK.ACTION, ADMIN_TASK.DESCRIPTION, ADMIN_TASK.CREATED_AT, ADMIN_TASK.STATUS,
			PERSON.FIRST_NAME, PERSON.LAST_NAME, ADMIN_TASK.DONE_AT)
			.from(ADMIN_TASK).leftJoin(PERSON).on(PERSON.ID.eq(ADMIN_TASK.DONE_BY))
			.where(ADMIN_TASK.STATUS.eq("open").or(ADMIN_TASK.UPDATED_AT.gt(OffsetDateTime.now().minusDays(7))))
			.orderBy(ADMIN_TASK.STATUS.desc(), ADMIN_TASK.TOOL, ADMIN_TASK.CREATED_AT)
			.limit(500)
			.fetch { QueueTask(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5()!!, it.value6()!!, it.value7()?.let { f -> "$f ${it.value8()}" }, it.value9()) }

	/** Opens a member task unless the same one is already open. */
	fun open(tool: Tool, action: String, person: UUID, resource: UUID, description: String) {
		dsl.insertInto(ADMIN_TASK)
			.set(ADMIN_TASK.TOOL, tool.name.lowercase()).set(ADMIN_TASK.ACTION, action).set(ADMIN_TASK.PERSON_ID, person).set(ADMIN_TASK.RESOURCE_ID, resource)
			.set(ADMIN_TASK.DESCRIPTION, description)
			.onConflictDoNothing()
			.execute()
	}

	@Transactional
	fun done(id: UUID, viewer: Viewer) {
		val task = openTask(id)
		dsl.update(ADMIN_TASK).set(ADMIN_TASK.STATUS, "done").set(ADMIN_TASK.DONE_BY, viewer.personId).set(ADMIN_TASK.DONE_AT, OffsetDateTime.now())
			.set(ADMIN_TASK.UPDATED_AT, OffsetDateTime.now()).where(ADMIN_TASK.ID.eq(id)).execute()
		val record = SYNC_RECORD.PERSON_ID.eq(task.personId).and(SYNC_RECORD.RESOURCE_ID.eq(task.resourceId))
		when (task.action) {
			// The person did it by hand: from now on it counts as the portal's, like an applied change.
			"add_member" -> dsl.update(SYNC_RECORD).set(SYNC_RECORD.ACTUAL, "present").set(SYNC_RECORD.STATUS, "in_sync")
				.set(SYNC_RECORD.APPLIED_AT, OffsetDateTime.now()).set(SYNC_RECORD.ATTEMPTS, 0).where(record).execute()
			"remove_member" -> dsl.update(SYNC_RECORD).set(SYNC_RECORD.ACTUAL, "absent").set(SYNC_RECORD.STATUS, "in_sync")
				.setNull(SYNC_RECORD.APPLIED_AT).where(record).execute()
		}
		audit(viewer, "queue.done", id, task.description)
	}

	@Transactional
	fun cancel(id: UUID, viewer: Viewer?, reason: String?) {
		val task = openTask(id)
		dsl.update(ADMIN_TASK).set(ADMIN_TASK.STATUS, "cancelled").set(ADMIN_TASK.CANCELLED_REASON, reason ?: "cancelled by national")
			.set(ADMIN_TASK.UPDATED_AT, OffsetDateTime.now()).where(ADMIN_TASK.ID.eq(id)).execute()
		if (viewer != null) audit(viewer, "queue.cancel", id, task.description)
	}

	/**
	 * Cancels open member tasks the plan no longer wants: adds for pairs not desired any more, removals for pairs that
	 * are desired again (PRD: "if the desired state changes before a task is done, the task cancels itself").
	 */
	fun reconcile(tool: Tool, desired: Set<Pair<UUID, UUID>>) {
		val open = dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.TOOL.eq(tool.name.lowercase()), ADMIN_TASK.STATUS.eq("open"), ADMIN_TASK.PERSON_ID.isNotNull).fetch()
		for (task in open) {
			val wanted = (task.personId!! to task.resourceId!!) in desired
			if ((task.action == "add_member" && !wanted) || (task.action == "remove_member" && wanted)) {
				dsl.update(ADMIN_TASK).set(ADMIN_TASK.STATUS, "cancelled").set(ADMIN_TASK.CANCELLED_REASON, "no longer needed")
					.set(ADMIN_TASK.UPDATED_AT, OffsetDateTime.now()).where(ADMIN_TASK.ID.eq(task.id)).execute()
			}
		}
	}

	/** Whether national confirmed at least [removals] removals for [tool] within the window. */
	fun removalsConfirmed(tool: Tool, removals: Int): Boolean =
		dsl.fetchExists(
			dsl.selectOne().from(ADMIN_TASK).join(SYNC_RUN).on(SYNC_RUN.ID.eq(ADMIN_TASK.RUN_ID))
				.where(
					ADMIN_TASK.TOOL.eq(tool.name.lowercase()), ADMIN_TASK.ACTION.eq("confirm_removals"), ADMIN_TASK.STATUS.eq("done"),
					ADMIN_TASK.DONE_AT.gt(OffsetDateTime.now().minus(CONFIRMATION_WINDOW)), SYNC_RUN.PLANNED_REMOVES.ge(removals),
				),
		)

	private fun openTask(id: UUID) =
		dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.ID.eq(id)).fetchOne()?.also {
			if (it.status != "open") throw ResponseStatusException(HttpStatus.CONFLICT, "This task is already ${it.status}")
		} ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such task")

	private fun audit(viewer: Viewer, action: String, id: UUID, description: String?) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "admin_task").set(AUDIT_EVENT.TARGET_ID, id)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(mapOf("task" to description))))
			.execute()
	}
}
