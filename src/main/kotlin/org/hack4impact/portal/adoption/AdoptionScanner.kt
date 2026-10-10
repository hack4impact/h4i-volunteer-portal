package org.hack4impact.portal.adoption

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.db.tables.references.ADOPTION_SNAPSHOT
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.DISCOVERED_RESOURCE
import org.hack4impact.portal.db.tables.references.DISCOVERY_SCAN
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** What one tool's scan found. Counts only: names and members stay in the database and the portal's screens. */
data class ScanReport(
	val tool: Tool,
	val scanId: UUID,
	val status: String,
	val resourcesFound: Int = 0,
	val matched: Int = 0,
	val unmatched: Int = 0,
	val snapshotted: Int = 0,
	val membersRead: Int = 0,
	val error: String? = null,
)

/**
 * The adoption scan (build plan step 7): lists what already exists in Google, GitHub and Slack, matches names
 * to chapters and projects by the naming convention, and snapshots the members of the given chapters' matched
 * and linked resources. It only reads from the tools. Decisions a lead made (linked, unmanaged) are kept.
 */
@Component
class AdoptionScanner(
	private val dsl: DSLContext,
	private val adapters: List<ReadAdapter>,
	private val transactions: TransactionTemplate,
) {
	companion object {
		/** Vaultwarden collection names are encrypted and Notion has no member API, so they're linked by hand. */
		val DISCOVERABLE = setOf(Tool.GOOGLE, Tool.GITHUB, Tool.SLACK)
	}

	/** Scans every discoverable tool; [chapterCodes] are the chapters whose resources' members are read. */
	fun scan(chapterCodes: Set<String>, tools: Set<Tool>? = null): List<ScanReport> {
		val chapters = dsl.select(CHAPTER.ID, CHAPTER.CODE).from(CHAPTER).where(CHAPTER.DELETED_AT.isNull).fetchMap(CHAPTER.CODE, CHAPTER.ID)
		val unknown = chapterCodes.map { it.lowercase() } - chapters.keys
		require(unknown.isEmpty()) { "No chapter ${unknown.joinToString()}" }
		val snapshotChapters = chapterCodes.map { chapters[it.lowercase()]!! }.toSet()
		return adapters
			.filter { it.tool in DISCOVERABLE && (tools == null || it.tool in tools) }
			.sortedBy { it.tool }
			.map { scanTool(it, snapshotChapters, chapterCodes.map { c -> c.lowercase() }.sorted()) }
	}

	/** Sets the extra name prefixes a chapter's resources use (besides its code). */
	fun setPrefixes(chapterCode: String, prefixes: Set<String>) {
		val updated = dsl.update(CHAPTER).set(CHAPTER.NAME_PREFIXES, prefixes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().toTypedArray())
			.where(CHAPTER.CODE.eq(chapterCode.lowercase())).execute()
		require(updated == 1) { "No chapter $chapterCode" }
	}

	/** Sets when a chapter's grandfathered access ends (PRD: e.g. the end of the first semester). */
	fun setGrandfatherUntil(chapterCode: String, until: LocalDate) {
		val updated = dsl.update(CHAPTER).set(CHAPTER.GRANDFATHER_UNTIL, until).where(CHAPTER.CODE.eq(chapterCode.lowercase())).execute()
		require(updated == 1) { "No chapter $chapterCode" }
	}

	private fun scanTool(adapter: ReadAdapter, snapshotChapters: Set<UUID>, chapterCodes: List<String>): ScanReport {
		val tool = adapter.tool
		val scanId = dsl.insertInto(DISCOVERY_SCAN).set(DISCOVERY_SCAN.TOOL, tool.name.lowercase()).set(DISCOVERY_SCAN.CHAPTERS, chapterCodes.toTypedArray())
			.returningResult(DISCOVERY_SCAN.ID).fetchSingle().value1()!!
		return try {
			val accounts = adapter.accounts()
			val resources = adapter.resources()
			val toSnapshot = transactions.execute { discover(tool, scanId, resources, snapshotChapters) }!!
			val accountsById = accounts.filter { it.externalId != null }.associateBy { it.externalId!! }
			val read = mutableMapOf<UUID, List<ResourceMember>>()
			val gone = mutableListOf<UUID>()
			for ((id, externalId) in toSnapshot) {
				try {
					read[id] = adapter.members(externalId)
				} catch (e: NotFound) {
					gone += id
				}
			}
			transactions.execute { snapshot(tool, scanId, read, gone, accountsById) }
			val counts = dsl.select(DISCOVERED_RESOURCE.STATE).from(DISCOVERED_RESOURCE)
				.where(DISCOVERED_RESOURCE.TOOL.eq(tool.name.lowercase()), DISCOVERED_RESOURCE.LAST_SCAN_ID.eq(scanId)).fetch(DISCOVERED_RESOURCE.STATE)
			finish(scanId, "completed", resources.size, read.values.sumOf { it.size }, null)
			ScanReport(
				tool, scanId, "completed", resources.size,
				matched = counts.count { it != "unmatched" }, unmatched = counts.count { it == "unmatched" },
				snapshotted = read.size, membersRead = read.values.sumOf { it.size },
			)
		} catch (e: AdapterException) {
			finish(scanId, "failed", 0, 0, e.message)
			ScanReport(tool, scanId, "failed", error = e.message)
		}
	}

	/** Records every resource seen, re-matches the ones no lead has decided on, and returns those to snapshot. */
	private fun discover(tool: Tool, scanId: UUID, resources: List<ToolResource>, snapshotChapters: Set<UUID>): List<Pair<UUID, String>> {
		val name = tool.name.lowercase()
		val now = OffsetDateTime.now()
		for (r in resources) {
			dsl.insertInto(DISCOVERED_RESOURCE)
				.set(DISCOVERED_RESOURCE.TOOL, name).set(DISCOVERED_RESOURCE.EXTERNAL_ID, r.externalId).set(DISCOVERED_RESOURCE.NAME, r.name)
				.set(DISCOVERED_RESOURCE.ARCHIVED, r.archived).set(DISCOVERED_RESOURCE.LAST_SCAN_ID, scanId)
				.onConflict(DISCOVERED_RESOURCE.TOOL, DISCOVERED_RESOURCE.EXTERNAL_ID).doUpdate()
				.set(DISCOVERED_RESOURCE.NAME, r.name).set(DISCOVERED_RESOURCE.ARCHIVED, r.archived)
				.set(DISCOVERED_RESOURCE.LAST_SEEN_AT, now).set(DISCOVERED_RESOURCE.GONE_AT, null as OffsetDateTime?)
				.set(DISCOVERED_RESOURCE.LAST_SCAN_ID, scanId)
				.execute()
		}
		dsl.update(DISCOVERED_RESOURCE).set(DISCOVERED_RESOURCE.GONE_AT, now)
			.where(DISCOVERED_RESOURCE.TOOL.eq(name), DISCOVERED_RESOURCE.LAST_SCAN_ID.ne(scanId), DISCOVERED_RESOURCE.GONE_AT.isNull)
			.execute()

		val (chapters, projects) = names(dsl)
		dsl.selectFrom(DISCOVERED_RESOURCE)
			.where(DISCOVERED_RESOURCE.TOOL.eq(name), DISCOVERED_RESOURCE.LAST_SCAN_ID.eq(scanId), DISCOVERED_RESOURCE.STATE.`in`("unmatched", "matched"))
			.fetch()
			.forEach { applyConvention(dsl, it.id!!, NamingConvention.match(tool, it.externalId!!, it.name!!, chapters, projects)) }

		return dsl.select(DISCOVERED_RESOURCE.ID, DISCOVERED_RESOURCE.EXTERNAL_ID).from(DISCOVERED_RESOURCE)
			.where(
				DISCOVERED_RESOURCE.TOOL.eq(name), DISCOVERED_RESOURCE.LAST_SCAN_ID.eq(scanId),
				DISCOVERED_RESOURCE.STATE.`in`("matched", "linked"), DISCOVERED_RESOURCE.CHAPTER_ID.`in`(snapshotChapters),
				DISCOVERED_RESOURCE.ARCHIVED.isFalse,
			)
			.fetch { it.value1()!! to it.value2()!! }
	}

	private fun snapshot(tool: Tool, scanId: UUID, read: Map<UUID, List<ResourceMember>>, gone: List<UUID>, accounts: Map<String, ToolAccount>) {
		val matcher = AccountMatcher.load(dsl, tool)
		for ((resourceId, members) in read) {
			for (m in members) {
				val account = accounts[m.accountId]
				val login = m.login ?: account?.login
				val person = matcher.match(m.accountId, login, account?.email)
				dsl.insertInto(ADOPTION_SNAPSHOT)
					.set(ADOPTION_SNAPSHOT.DISCOVERED_RESOURCE_ID, resourceId).set(ADOPTION_SNAPSHOT.SCAN_ID, scanId)
					.set(ADOPTION_SNAPSHOT.ACCOUNT_ID, m.accountId).set(ADOPTION_SNAPSHOT.LOGIN, login)
					.set(ADOPTION_SNAPSHOT.PERSON_ID, person?.personId).set(ADOPTION_SNAPSHOT.MATCHED_BY, person?.by?.name?.lowercase())
					.set(ADOPTION_SNAPSHOT.ACCESS, m.access.name.lowercase())
					.onConflictDoNothing()
					.execute()
			}
			dsl.update(DISCOVERED_RESOURCE).set(DISCOVERED_RESOURCE.SNAPSHOT_SCAN_ID, scanId).where(DISCOVERED_RESOURCE.ID.eq(resourceId)).execute()
		}
		if (gone.isNotEmpty()) {
			dsl.update(DISCOVERED_RESOURCE).set(DISCOVERED_RESOURCE.GONE_AT, OffsetDateTime.now()).where(DISCOVERED_RESOURCE.ID.`in`(gone)).execute()
		}
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, "sync").set(AUDIT_EVENT.ACTION, "adoption.scan")
			.set(AUDIT_EVENT.TARGET_TYPE, "discovery_scan").set(AUDIT_EVENT.TARGET_ID, scanId)
			.set(AUDIT_EVENT.AFTER, JSONB.valueOf("""{"tool":"${tool.name.lowercase()}","snapshotted":${read.size},"members":${read.values.sumOf { it.size }}}"""))
			.execute()
	}

	private fun finish(scanId: UUID, status: String, found: Int, members: Int, error: String?) {
		dsl.update(DISCOVERY_SCAN)
			.set(DISCOVERY_SCAN.STATUS, status).set(DISCOVERY_SCAN.RESOURCES_FOUND, found).set(DISCOVERY_SCAN.MEMBERS_READ, members)
			.set(DISCOVERY_SCAN.ERROR, error).set(DISCOVERY_SCAN.FINISHED_AT, OffsetDateTime.now())
			.where(DISCOVERY_SCAN.ID.eq(scanId))
			.execute()
	}
}

/** Every chapter's and project's names, for the naming convention. */
internal fun names(dsl: DSLContext): Pair<List<ChapterNames>, List<ProjectNames>> {
	val chapters = dsl.select(CHAPTER.ID, CHAPTER.CODE, CHAPTER.NAME_PREFIXES).from(CHAPTER).where(CHAPTER.DELETED_AT.isNull)
		.fetch { ChapterNames(it.value1()!!, it.value2()!!, it.value3()!!.filterNotNull().toSet()) }
	val projects = dsl.select(PROJECT.ID, PROJECT.CHAPTER_ID, PROJECT.SLUG).from(PROJECT)
		.fetch { ProjectNames(it.value1()!!, it.value2()!!, it.value3()!!) }
	return chapters to projects
}

/** Puts a resource back under the naming convention: matched if the convention finds a chapter, else unmatched. */
internal fun applyConvention(dsl: DSLContext, id: UUID, match: ConventionMatch?) {
	dsl.update(DISCOVERED_RESOURCE)
		.set(DISCOVERED_RESOURCE.STATE, if (match == null) "unmatched" else "matched")
		.set(DISCOVERED_RESOURCE.CHAPTER_ID, match?.chapterId)
		.set(DISCOVERED_RESOURCE.PROJECT_ID, match?.projectId)
		.set(DISCOVERED_RESOURCE.TARGET, match?.target?.name?.lowercase())
		.set(DISCOVERED_RESOURCE.MATCH_METHOD, if (match == null) null else "convention")
		.set(DISCOVERED_RESOURCE.SUGGESTED_SLUG, match?.suggestedSlug)
		.set(DISCOVERED_RESOURCE.LINKED_BY, null as UUID?)
		.set(DISCOVERED_RESOURCE.LINKED_AT, null as OffsetDateTime?)
		.where(DISCOVERED_RESOURCE.ID.eq(id))
		.execute()
}
