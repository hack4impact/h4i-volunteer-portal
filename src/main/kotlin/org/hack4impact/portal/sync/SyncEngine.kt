package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Membership
import org.hack4impact.portal.resolver.Plan
import org.hack4impact.portal.resolver.Planner
import org.hack4impact.portal.resolver.Reason
import org.hack4impact.portal.resolver.Resolution
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/** What one tool's run found. Counts are for the whole tool; details are in sync_change. */
data class ToolRunReport(
	val tool: Tool,
	val runId: UUID?,
	val status: String,
	val adds: Int = 0,
	val changes: Int = 0,
	val removals: Int = 0,
	val drift: Int = 0,
	val unmatchedAccounts: Int = 0,
	val missingResources: Int = 0,
	/** Why a real (writing) run would stop for national's confirmation: the blast-radius limit. */
	val wouldPause: String? = null,
	val error: String? = null,
)

/**
 * The sync engine (build plan step 6), dry run only: resolves what access should exist, reads what each tool
 * actually has, plans the difference, and records the plan. It never writes to a tool; write adapters arrive in
 * step 9. Each tool runs and is recorded on its own, so one failing tool doesn't stop the others.
 */
@Component
class SyncEngine(
	private val dsl: DSLContext,
	private val worlds: WorldLoader,
	private val adapters: List<ReadAdapter>,
	private val transactions: TransactionTemplate,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val json = JsonMapper.builder().build()

	private data class ManagedResource(val id: UUID, val externalId: String)

	private data class Actual(val memberships: List<Membership>, val unmatched: List<Pair<UUID, String>>, val missing: List<UUID>)

	fun dryRun(trigger: String, tools: Set<Tool>? = null): List<ToolRunReport> {
		val loaded = worlds.load()
		loaded.warnings.forEach { log.warn("Sync: {}", it) }
		val resolution = Resolver.resolve(loaded.world)
		val resourceTool = loaded.world.resources.associate { it.id to it.tool }
		return adapters
			.filter { tools == null || it.tool in tools }
			.sortedBy { it.tool }
			.map { adapter -> runTool(adapter, trigger, resolution, resourceTool) }
	}

	private fun runTool(adapter: ReadAdapter, trigger: String, resolution: Resolution, resourceTool: Map<UUID, Tool>): ToolRunReport {
		val tool = adapter.tool
		val setting = dsl.selectFrom(TOOL_SETTING).where(TOOL_SETTING.TOOL.eq(tool.name.lowercase())).fetchOne()
		if (setting?.enabled == false) {
			val runId = recordRun(tool, trigger, "paused", null, "paused by the kill switch${setting.pauseReason?.let { ": $it" } ?: ""}")
			return ToolRunReport(tool, runId, "paused", error = "paused by the kill switch")
		}
		val actual = try {
			readActual(adapter)
		} catch (e: AdapterException) {
			val runId = recordRun(tool, trigger, "failed", null, e.message)
			return ToolRunReport(tool, runId, "failed", error = e.message)
		}

		val ofTool = { id: UUID -> resourceTool[id] == tool }
		val desired = Resolution(resolution.grants.filter { ofTool(it.resourceId) }, resolution.withheld.filter { ofTool(it.resourceId) })
		val previous = dsl.select(SYNC_RECORD.PERSON_ID, SYNC_RECORD.RESOURCE_ID, SYNC_RECORD.REASONS).from(SYNC_RECORD)
			.where(SYNC_RECORD.APPLIED_AT.isNotNull)
			.fetch()
			.filter { ofTool(it.value2()!!) }
			.associate { (it.value1()!! to it.value2()!!) to reasonsFrom(it.value3()) }
		val plan = Planner.plan(desired, actual.memberships, previous)
		val wouldPause = blastRadius(plan, actual.memberships, setting?.maxRemovals ?: 25, setting?.maxRemovalRatio?.toDouble() ?: 0.2)

		val runId = transactions.execute { record(tool, trigger, plan, actual, desired, wouldPause) }!!
		return ToolRunReport(
			tool, runId, if (wouldPause == null) "completed" else "paused",
			plan.adds.size, plan.changes.size, plan.removals.size, plan.drift.size, actual.unmatched.size, actual.missing.size, wouldPause,
		)
	}

	/** Reads members of every resource the portal manages in this tool, mapped to people through their tool accounts. */
	private fun readActual(adapter: ReadAdapter): Actual {
		// Always reach the tool, even with nothing to compare yet, so bad credentials or an outage show as a failed run.
		adapter.accounts()
		val tool = adapter.tool.name.lowercase()
		val managed = dsl.select(RESOURCE.ID, RESOURCE.EXTERNAL_ID).from(RESOURCE)
			.where(RESOURCE.TOOL.eq(tool), RESOURCE.EXTERNAL_ID.isNotNull, RESOURCE.ARCHIVED_AT.isNull, RESOURCE.MANAGED.ne("unmanaged"))
			.fetch { ManagedResource(it.value1()!!, it.value2()!!) }
		val owner = dsl.select(TOOL_ACCOUNT.EXTERNAL_ID, TOOL_ACCOUNT.PERSON_ID).from(TOOL_ACCOUNT)
			.where(TOOL_ACCOUNT.TOOL.eq(tool), TOOL_ACCOUNT.EXTERNAL_ID.isNotNull)
			.fetchMap(TOOL_ACCOUNT.EXTERNAL_ID, TOOL_ACCOUNT.PERSON_ID)
		val memberships = mutableListOf<Membership>()
		val unmatched = mutableListOf<Pair<UUID, String>>()
		val missing = mutableListOf<UUID>()
		for (resource in managed) {
			val members = try {
				adapter.members(resource.externalId)
			} catch (e: NotFound) {
				missing += resource.id
				continue
			}
			for (member in members) {
				val person = owner[member.accountId]
				if (person == null) unmatched += resource.id to member.accountId else memberships += Membership(person, resource.id, member.access)
			}
		}
		return Actual(memberships, unmatched, missing)
	}

	/** Why a writing run would pause for national: too many removals overall, or too large a share of one resource. */
	private fun blastRadius(plan: Plan, actual: List<Membership>, maxRemovals: Int, maxRatio: Double): String? {
		if (plan.removals.size > maxRemovals) return "${plan.removals.size} removals is over the limit of $maxRemovals"
		val membersPerResource = actual.groupingBy { it.resourceId }.eachCount()
		return plan.removals.groupingBy { it.resourceId }.eachCount().entries.firstNotNullOfOrNull { (resource, removed) ->
			val total = membersPerResource[resource] ?: return@firstNotNullOfOrNull null
			if (total >= 5 && removed.toDouble() / total > maxRatio) "$removed of $total members of one resource would be removed (over ${(maxRatio * 100).toInt()}%)" else null
		}
	}

	private fun record(tool: Tool, trigger: String, plan: Plan, actual: Actual, desired: Resolution, wouldPause: String?): UUID {
		val runId = recordRun(tool, trigger, if (wouldPause == null) "completed" else "paused", plan, wouldPause)
		fun change(kind: String, person: UUID?, resource: UUID?, account: String? = null, from: Access? = null, to: Access? = null, reasons: List<Reason> = emptyList()) =
			dsl.insertInto(SYNC_CHANGE)
				.set(SYNC_CHANGE.RUN_ID, runId).set(SYNC_CHANGE.KIND, kind).set(SYNC_CHANGE.PERSON_ID, person).set(SYNC_CHANGE.RESOURCE_ID, resource)
				.set(SYNC_CHANGE.ACCOUNT_ID, account).set(SYNC_CHANGE.FROM_ACCESS, from?.name?.lowercase()).set(SYNC_CHANGE.TO_ACCESS, to?.name?.lowercase())
				.set(SYNC_CHANGE.REASONS, reasonsJson(reasons))
		val queries = buildList {
			plan.adds.forEach { add(change("add", it.personId, it.resourceId, to = it.access, reasons = it.reasons)) }
			plan.changes.forEach { add(change("change", it.grant.personId, it.grant.resourceId, from = it.from, to = it.grant.access, reasons = it.grant.reasons)) }
			plan.removals.forEach { add(change("remove", it.personId, it.resourceId, from = it.access, reasons = it.previousReasons)) }
			plan.drift.forEach { add(change("drift", it.personId, it.resourceId, from = it.access)) }
			actual.unmatched.forEach { (resource, account) -> add(change("unmatched_account", null, resource, account = account)) }
			actual.missing.forEach { add(change("missing_resource", null, it)) }
		}
		dsl.batch(queries).execute()

		// One sync record per desired grant: what should be, what is, and why. Dry runs never set applied_at.
		val have = actual.memberships.associateBy { it.personId to it.resourceId }
		val now = OffsetDateTime.now()
		desired.grants.forEach { grant ->
			val current = have[grant.personId to grant.resourceId]
			val inSync = current?.access == grant.access
			dsl.insertInto(SYNC_RECORD)
				.set(SYNC_RECORD.PERSON_ID, grant.personId).set(SYNC_RECORD.RESOURCE_ID, grant.resourceId)
				.set(SYNC_RECORD.DESIRED, "present").set(SYNC_RECORD.DESIRED_ACCESS_LEVEL, grant.access.name.lowercase())
				.set(SYNC_RECORD.REASONS, reasonsJson(grant.reasons))
				.set(SYNC_RECORD.ACTUAL, if (current == null) "absent" else "present")
				.set(SYNC_RECORD.STATUS, if (inSync) "in_sync" else "pending")
				.set(SYNC_RECORD.LAST_ATTEMPT_AT, now).set(SYNC_RECORD.LAST_RUN_ID, runId)
				.onConflict(SYNC_RECORD.PERSON_ID, SYNC_RECORD.RESOURCE_ID).doUpdate()
				.set(SYNC_RECORD.DESIRED, "present").set(SYNC_RECORD.DESIRED_ACCESS_LEVEL, grant.access.name.lowercase())
				.set(SYNC_RECORD.REASONS, reasonsJson(grant.reasons))
				.set(SYNC_RECORD.ACTUAL, if (current == null) "absent" else "present")
				.set(SYNC_RECORD.STATUS, if (inSync) "in_sync" else "pending")
				.set(SYNC_RECORD.LAST_ATTEMPT_AT, now).set(SYNC_RECORD.LAST_RUN_ID, runId)
				.execute()
		}
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, "sync").set(AUDIT_EVENT.ACTION, "sync.dry_run")
			.set(AUDIT_EVENT.TARGET_TYPE, "sync_run").set(AUDIT_EVENT.TARGET_ID, runId)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf("""{"tool":"${tool.name.lowercase()}","adds":${plan.adds.size},"changes":${plan.changes.size},"removals":${plan.removals.size},"drift":${plan.drift.size}}"""))
			.execute()
		return runId
	}

	private fun recordRun(tool: Tool, trigger: String, status: String, plan: Plan?, error: String?): UUID =
		dsl.insertInto(SYNC_RUN)
			.set(SYNC_RUN.TOOL, tool.name.lowercase()).set(SYNC_RUN.MODE, "dry_run").set(SYNC_RUN.TRIGGER, trigger).set(SYNC_RUN.STATUS, status)
			.set(SYNC_RUN.FINISHED_AT, OffsetDateTime.now())
			.set(SYNC_RUN.PLANNED_ADDS, (plan?.adds?.size ?: 0) + (plan?.changes?.size ?: 0))
			.set(SYNC_RUN.PLANNED_REMOVES, plan?.removals?.size ?: 0)
			.set(SYNC_RUN.ERROR, error)
			.returningResult(SYNC_RUN.ID).fetchSingle().value1()!!

	private fun reasonsJson(reasons: List<Reason>): JSONB = JSONB.valueOf(json.writeValueAsString(reasons.map(::reasonMap)))

	private fun reasonMap(reason: Reason): Map<String, String> = when (reason) {
		is Reason.ChapterMember -> mapOf("type" to "chapter_member", "chapterId" to reason.chapterId.toString())
		is Reason.ChapterLead -> mapOf("type" to "chapter_lead", "chapterId" to reason.chapterId.toString())
		is Reason.ProjectMember -> mapOf("type" to "project_member", "projectId" to reason.projectId.toString())
		is Reason.FromRule -> mapOf("type" to "rule", "ruleId" to reason.ruleId.toString())
		is Reason.NationalDefault -> mapOf("type" to "national_default", "ruleId" to reason.ruleId.toString())
	}

	private fun reasonsFrom(value: JSONB?): List<Reason> {
		if (value == null) return emptyList()
		return buildList {
			json.readTree(value.data()).forEach { r ->
				val id = { field: String -> UUID.fromString(r.path(field).asString()) }
				when (r.path("type").asString()) {
					"chapter_member" -> add(Reason.ChapterMember(id("chapterId")))
					"chapter_lead" -> add(Reason.ChapterLead(id("chapterId")))
					"project_member" -> add(Reason.ProjectMember(id("projectId")))
					"rule" -> add(Reason.FromRule(id("ruleId")))
					"national_default" -> add(Reason.NationalDefault(id("ruleId")))
				}
			}
		}
	}
}
