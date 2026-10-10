package org.hack4impact.portal.adoption

import org.hack4impact.portal.db.tables.references.ADOPTION_SNAPSHOT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.DISCOVERED_RESOURCE
import org.hack4impact.portal.db.tables.references.DISCOVERY_SCAN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Audience
import org.hack4impact.portal.resolver.ChapterAudience
import org.hack4impact.portal.resolver.ChapterResource
import org.hack4impact.portal.resolver.ProjectResource
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.resolver.Resource
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.sync.WorldLoader
import org.hack4impact.portal.db.tables.records.DiscoveredResourceRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** One chapter's adoption report: what exists in its tools, who is in it, and what adoption would mean for them. */
data class AdoptionReport(
	/** Grandfathered access lasts until this date: the chapter's own, else portal.adoption.default-grandfather-until; null = neither set. */
	val grandfatheredUntil: LocalDate?,
	val scans: List<ScanStatus>,
	val resources: List<AdoptionResource>,
	/** The chapter's projects, for linking. */
	val projects: List<ProjectOption>,
	/** Leads, co-leads and national can link resources; viewers only read. */
	val canEdit: Boolean,
)

data class ScanStatus(
	val tool: String,
	val status: String,
	val ranAt: OffsetDateTime,
	val error: String?,
	val resourcesFound: Int,
	/** Whether that scan read the members of this chapter's resources. */
	val coversChapter: Boolean,
)

data class ProjectOption(val id: UUID, val name: String, val slug: String, val status: String)

data class AdoptionResource(
	val id: UUID,
	val tool: String,
	val name: String,
	/** matched (naming convention), linked (by hand), unmanaged or adopted. */
	val state: String,
	/** chapter_members, chapter_leads or project_team; null = a lead has to decide. */
	val target: String?,
	val projectId: UUID?,
	val projectName: String?,
	val suggestedSlug: String?,
	val matchMethod: String?,
	val archived: Boolean,
	/** The latest scan didn't find it in the tool. */
	val gone: Boolean,
	/** When its members were last read; null = not read yet. */
	val snapshotAt: OffsetDateTime?,
	val expected: Int,
	val grandfathered: Int,
	val unknownAccounts: Int,
	/** People who should have it but aren't in it; null until members have been read. */
	val wouldAdd: Int?,
)

/** [verdict]: expected (should have it and does), grandfathered (has it, isn't in the desired set), unknown_account, would_add. */
data class AdoptionPerson(
	val personId: UUID?,
	val name: String?,
	/** The tool login; addresses outside hack4impact.org are partly hidden. */
	val login: String?,
	val verdict: String,
	val matchedBy: String?,
	val access: String?,
)

data class UnmatchedResource(val id: UUID, val tool: String, val name: String, val archived: Boolean)

@Component
class AdoptionReports(
	private val dsl: DSLContext,
	private val worlds: WorldLoader,
	@Value("\${portal.adoption.default-grandfather-until:}") defaultGrandfatherUntil: String,
) {
	private val defaultUntil = defaultGrandfatherUntil.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }

	private data class Snapshot(val accountId: String, val login: String?, val personId: UUID?, val matchedBy: String?, val access: String)

	fun report(chapterId: UUID, canEdit: Boolean): AdoptionReport {
		val code = dsl.select(CHAPTER.CODE).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle().value1()!!
		val rows = chapterResources(chapterId)
		val desired = desired(chapterId, rows)
		val snapshots = snapshots(rows)
		val projects = dsl.select(PROJECT.ID, PROJECT.NAME, PROJECT.SLUG, PROJECT.STATUS).from(PROJECT).where(PROJECT.CHAPTER_ID.eq(chapterId)).orderBy(PROJECT.NAME)
			.fetch { ProjectOption(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!) }
		val projectNames = projects.associate { it.id to it.name }
		val snapshotTimes = rows.mapNotNull { it.snapshotScanId }.distinct().let { ids ->
			dsl.select(DISCOVERY_SCAN.ID, DISCOVERY_SCAN.STARTED_AT).from(DISCOVERY_SCAN).where(DISCOVERY_SCAN.ID.`in`(ids)).fetchMap(DISCOVERY_SCAN.ID, DISCOVERY_SCAN.STARTED_AT)
		}
		val scans = dsl.selectFrom(DISCOVERY_SCAN).orderBy(DISCOVERY_SCAN.STARTED_AT.desc()).limit(200).fetch().distinctBy { it.tool }
			.map { ScanStatus(it.tool!!, it.status!!, it.startedAt!!, it.error, it.resourcesFound!!, code in it.chapters!!) }
			.sortedBy { it.tool }

		return AdoptionReport(
			grandfatheredUntil = dsl.select(CHAPTER.GRANDFATHER_UNTIL).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle().value1() ?: defaultUntil,
			scans = scans,
			resources = rows.map { r ->
				val members = snapshots[r.id]
				val want = desired[r.id].orEmpty()
				AdoptionResource(
					r.id!!, r.tool!!, r.name!!, r.state!!, r.target, r.projectId, r.projectId?.let { projectNames[it] }, r.suggestedSlug, r.matchMethod,
					r.archived!!, r.goneAt != null, r.snapshotScanId?.let { snapshotTimes[it] },
					expected = members?.count { it.personId != null && it.personId in want } ?: 0,
					grandfathered = members?.count { it.personId != null && it.personId !in want } ?: 0,
					unknownAccounts = members?.count { it.personId == null } ?: 0,
					wouldAdd = members?.let { m -> (want - m.mapNotNull { it.personId }.toSet()).size },
				)
			},
			projects = projects,
			canEdit = canEdit,
		)
	}

	/** Everyone behind one resource, with a verdict each. Null when the resource isn't this chapter's. */
	fun people(chapterId: UUID, resourceId: UUID): List<AdoptionPerson>? {
		val row = chapterResources(chapterId).firstOrNull { it.id == resourceId } ?: return null
		val want = desired(chapterId, listOf(row))[row.id].orEmpty()
		val members = snapshots(listOf(row))[row.id] ?: return emptyList()
		val ids = (members.mapNotNull { it.personId } + want).toSet()
		val names = dsl.select(PERSON.ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME).from(PERSON).where(PERSON.ID.`in`(ids))
			.fetchMap({ it.value1()!! }, { "${it.value4()?.takeIf { p -> p.isNotBlank() } ?: it.value2()} ${it.value3()}" })
		val inTool = members.map {
			val verdict = when {
				it.personId == null -> "unknown_account"
				it.personId in want -> "expected"
				else -> "grandfathered"
			}
			AdoptionPerson(it.personId, it.personId?.let { p -> names[p] }, mask(it.login), verdict, it.matchedBy, it.access)
		}
		val missing = (want - members.mapNotNull { it.personId }.toSet()).map { AdoptionPerson(it, names[it], null, "would_add", null, null) }
		val order = listOf("grandfathered", "unknown_account", "would_add", "expected")
		return (inTool + missing).sortedWith(compareBy({ order.indexOf(it.verdict) }, { it.name ?: it.login ?: "" }))
	}

	fun unmatched(): List<UnmatchedResource> =
		dsl.select(DISCOVERED_RESOURCE.ID, DISCOVERED_RESOURCE.TOOL, DISCOVERED_RESOURCE.NAME, DISCOVERED_RESOURCE.ARCHIVED).from(DISCOVERED_RESOURCE)
			.where(DISCOVERED_RESOURCE.STATE.eq("unmatched"), DISCOVERED_RESOURCE.GONE_AT.isNull)
			.orderBy(DISCOVERED_RESOURCE.TOOL, DISCOVERED_RESOURCE.NAME)
			.fetch { UnmatchedResource(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!) }

	private fun chapterResources(chapterId: UUID): List<DiscoveredResourceRecord> =
		dsl.selectFrom(DISCOVERED_RESOURCE).where(DISCOVERED_RESOURCE.CHAPTER_ID.eq(chapterId))
			.orderBy(DISCOVERED_RESOURCE.TOOL, DISCOVERED_RESOURCE.NAME).fetch()

	private fun snapshots(rows: List<DiscoveredResourceRecord>): Map<UUID, List<Snapshot>> {
		val scans = rows.filter { it.snapshotScanId != null }.associate { it.id!! to it.snapshotScanId!! }
		if (scans.isEmpty()) return emptyMap()
		return dsl.selectFrom(ADOPTION_SNAPSHOT).where(ADOPTION_SNAPSHOT.DISCOVERED_RESOURCE_ID.`in`(scans.keys)).fetch()
			.filter { scans[it.discoveredResourceId] == it.scanId }
			.groupBy({ it.discoveredResourceId!! }, { Snapshot(it.accountId!!, it.login, it.personId, it.matchedBy, it.access!!) })
			.let { found -> scans.keys.associateWith { found[it].orEmpty() } }
	}

	/**
	 * Who should have each resource if it were adopted as matched or linked: the resolver's answer for today's
	 * data, with each not-yet-adopted resource attached to its target. A resource without a target wants nobody.
	 */
	private fun desired(chapterId: UUID, rows: List<DiscoveredResourceRecord>): Map<UUID, Set<UUID>> {
		val proposals = rows.filter { it.target != null && it.state in setOf("matched", "linked") && it.resourceId == null }
		val adopted = rows.filter { it.resourceId != null }.associate { it.resourceId!! to it.id!! }
		if (proposals.isEmpty() && adopted.isEmpty()) return emptyMap()
		val base = worlds.load().world
		val world = base.copy(
			resources = base.resources + proposals.map { Resource(it.id!!, Tool.valueOf(it.tool!!.uppercase()), chapterId, archived = it.archived!!) },
			chapterResources = base.chapterResources + proposals.filter { it.target != "project_team" }.map {
				ChapterResource(chapterId, it.id!!, if (it.target == "chapter_leads") ChapterAudience.LEADS else ChapterAudience.MEMBERS, Access.WRITE)
			},
			projectResources = base.projectResources + proposals.filter { it.target == "project_team" }.map {
				ProjectResource(it.projectId!!, it.id!!, Audience.Team, Access.WRITE)
			},
		)
		return Resolver.resolve(world).grants
			.mapNotNull { g -> (adopted[g.resourceId] ?: g.resourceId.takeIf { id -> proposals.any { it.id == id } })?.let { it to g.personId } }
			.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
	}

	companion object {
		private val H4I = Regex("(^|\\.)hack4impact\\.org$")

		/** Leaves hack4impact.org addresses and plain logins as they are; hides most of any other address. */
		fun mask(login: String?): String? {
			if (login == null || '@' !in login) return login
			val (local, domain) = login.substringBeforeLast('@') to login.substringAfterLast('@')
			return if (H4I.containsMatchIn(domain.lowercase())) login else "${local.take(1)}…@$domain"
		}
	}
}
