package org.hack4impact.portal.projects

import org.hack4impact.portal.chapters.PlannedChange
import org.hack4impact.portal.db.tables.records.ProjectRecord
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.DISCOVERED_RESOURCE
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.notion.NotionRoutes
import org.hack4impact.portal.notion.NotionSettings
import org.hack4impact.portal.resolver.ProjectStatus
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.resolver.WithheldReason
import org.hack4impact.portal.sync.WorldLoader
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Reads for the projects screens. */
@Component
class ProjectQueries(private val dsl: DSLContext, private val worlds: WorldLoader, private val notion: NotionSettings) {
	fun bySlug(chapterId: UUID, slug: String): ProjectRecord? =
		dsl.selectFrom(PROJECT).where(PROJECT.CHAPTER_ID.eq(chapterId), PROJECT.SLUG.eq(slug.lowercase())).fetchOne()

	fun list(chapterId: UUID): List<ProjectSummary> {
		val members = DSL.selectCount().from(PROJECT_MEMBER).where(PROJECT_MEMBER.PROJECT_ID.eq(PROJECT.ID), PROJECT_MEMBER.REMOVED_AT.isNull).asField<Int>()
		val resources = DSL.selectCount().from(PROJECT_RESOURCE).where(PROJECT_RESOURCE.PROJECT_ID.eq(PROJECT.ID)).asField<Int>()
		val order = DSL.case_(PROJECT.STATUS).`when`("active", 0).`when`("paused", 1).`when`("draft", 2).else_(3)
		return dsl.select(PROJECT.ID, PROJECT.NAME, PROJECT.SLUG, PROJECT.STATUS, PROJECT.TYPE, PROJECT.TAGS, TERM.SEASON, TERM.YEAR, PARTNER.NAME, members, resources, PROJECT.UPDATED_AT)
			.from(PROJECT).leftJoin(TERM).on(TERM.ID.eq(PROJECT.TERM_ID)).leftJoin(PARTNER).on(PARTNER.ID.eq(PROJECT.PARTNER_ID))
			.where(PROJECT.CHAPTER_ID.eq(chapterId))
			.orderBy(order, PROJECT.NAME)
			.fetch {
				ProjectSummary(
					it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!, it.value5(), it.value6()!!.filterNotNull(),
					termLabel(it.value7(), it.value8()?.toInt()), it.value9(), it.value10() ?: 0, it.value11() ?: 0, it.value12()!!,
				)
			}
	}

	fun detail(chapterId: UUID, project: ProjectRecord, canEdit: Boolean): ProjectDetail {
		val id = project.id!!
		val chapterCode = dsl.select(CHAPTER.CODE).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle().value1()!!
		val term = project.termId?.let { t -> dsl.select(TERM.SEASON, TERM.YEAR).from(TERM).where(TERM.ID.eq(t)).fetchOne()?.let { termLabel(it.value1(), it.value2()?.toInt()) } }
		val partner = project.partnerId?.let { p -> dsl.select(PARTNER.NAME).from(PARTNER).where(PARTNER.ID.eq(p)).fetchOne()?.value1() }

		val members = dsl.select(
			PERSON.ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, PERSON.ORG_EMAIL, PERSON.SCHOOL_EMAIL, PERSON.STATUS,
			PROJECT_ROLE.ID, PROJECT_ROLE.NAME, PROJECT_ROLE.IS_LEAD, PROJECT_MEMBER.AGREEMENT_STATUS,
		).from(PROJECT_MEMBER).join(PERSON).on(PERSON.ID.eq(PROJECT_MEMBER.PERSON_ID))
			.leftJoin(PROJECT_ROLE).on(PROJECT_ROLE.ID.eq(PROJECT_MEMBER.PROJECT_ROLE_ID))
			.where(PROJECT_MEMBER.PROJECT_ID.eq(id), PROJECT_MEMBER.REMOVED_AT.isNull)
			.fetch {
				ProjectMemberRow(
					it.value1()!!, name(it.value4(), it.value2()!!, it.value3()!!), it.value5() ?: it.value6(), it.value7()!!,
					it.value8(), it.value9(), it.value10() == true, it.value11()!!,
				)
			}
			.sortedWith(compareBy({ !it.isLead }, { it.name }))

		// Who would get each resource. A draft project is previewed as if it were active.
		val loaded = worlds.load().world
		val asIfActive = project.status == "draft"
		val world = if (!asIfActive) loaded else loaded.copy(projects = loaded.projects.map { if (it.id == id) it.copy(status = ProjectStatus.ACTIVE) else it })
		val resolution = Resolver.resolve(world)

		val resourceRows = dsl.select(
			RESOURCE.ID, RESOURCE.TOOL, RESOURCE.NAME, RESOURCE.EXTERNAL_ID, RESOURCE.ARCHIVED_AT, RESOURCE.TAGS,
			PROJECT_RESOURCE.AUDIENCE, PROJECT_ROLE.ID, PROJECT_ROLE.NAME, PROJECT_RESOURCE.ACCESS_LEVEL, PROJECT_RESOURCE.REQUIRES_AGREEMENT,
		).from(PROJECT_RESOURCE).join(RESOURCE).on(RESOURCE.ID.eq(PROJECT_RESOURCE.RESOURCE_ID))
			.leftJoin(PROJECT_ROLE).on(PROJECT_ROLE.ID.eq(PROJECT_RESOURCE.AUDIENCE_ROLE_ID))
			.where(PROJECT_RESOURCE.PROJECT_ID.eq(id))
			.orderBy(RESOURCE.TOOL, RESOURCE.NAME)
			.fetch()
		val resourceIds = resourceRows.map { it.value1()!! }
		val shared = dsl.select(PROJECT_RESOURCE.RESOURCE_ID, PROJECT.NAME).from(PROJECT_RESOURCE).join(PROJECT).on(PROJECT.ID.eq(PROJECT_RESOURCE.PROJECT_ID))
			.where(PROJECT_RESOURCE.RESOURCE_ID.`in`(resourceIds), PROJECT_RESOURCE.PROJECT_ID.ne(id))
			.fetch().groupBy({ it.value1()!! }, { it.value2()!! })
		val discovered = dsl.select(DISCOVERED_RESOURCE.TOOL, DISCOVERED_RESOURCE.NAME, DISCOVERED_RESOURCE.EXTERNAL_ID).from(DISCOVERED_RESOURCE)
			.where(DISCOVERED_RESOURCE.GONE_AT.isNull).fetch()
			.flatMap { listOf(it.value1()!! to it.value2()!!.lowercase(), it.value1()!! to it.value3()!!.lowercase()) }.toSet()
		val resources = resourceRows.map { r ->
			val rid = r.value1()!!
			ProjectResourceRow(
				rid, r.value2()!!, r.value3()!!,
				state = when {
					r.value5() != null -> "archived"
					r.value4() == null -> "to_create"
					else -> "exists"
				},
				audience = r.value7()!!, roleId = r.value8(), role = r.value9(), access = r.value10()!!, requiresAgreement = r.value11()!!,
				tags = r.value6()!!.filterNotNull(), sharedWith = shared[rid].orEmpty().sorted(),
				existsInTool = r.value4() == null && (r.value2()!! to r.value3()!!.lowercase()) in discovered,
				getting = resolution.grants.count { it.resourceId == rid },
				awaitingAgreement = resolution.withheld.count { it.resourceId == rid && it.why is WithheldReason.AwaitingAgreement },
			)
		}
		val grants = resolution.grants.filter { it.resourceId in resourceIds }
		val preview = AccessPreview(
			people = grants.map { it.personId }.toSet().size, grants = grants.size,
			awaitingAgreement = resolution.withheld.count { it.resourceId in resourceIds && it.why is WithheldReason.AwaitingAgreement },
			asIfActive = asIfActive,
		)

		// The latest dry run of each tool, limited to this project's resources.
		val latest = dsl.selectFrom(SYNC_RUN).orderBy(SYNC_RUN.STARTED_AT.desc()).limit(500).fetch().filter { it.tool != null }.distinctBy { it.tool }
		val planned = if (resourceIds.isEmpty() || latest.isEmpty()) emptyList() else dsl.select(
			SYNC_CHANGE.KIND, RESOURCE.TOOL, RESOURCE.NAME, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME,
			SYNC_CHANGE.ACCOUNT_ID, SYNC_CHANGE.FROM_ACCESS, SYNC_CHANGE.TO_ACCESS,
		).from(SYNC_CHANGE).join(RESOURCE).on(RESOURCE.ID.eq(SYNC_CHANGE.RESOURCE_ID)).leftJoin(PERSON).on(PERSON.ID.eq(SYNC_CHANGE.PERSON_ID))
			.where(SYNC_CHANGE.RUN_ID.`in`(latest.map { it.id }), SYNC_CHANGE.RESOURCE_ID.`in`(resourceIds))
			.orderBy(RESOURCE.TOOL, RESOURCE.NAME, SYNC_CHANGE.KIND)
			.limit(500)
			.fetch {
				PlannedChange(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()?.let { f -> name(it.value6(), f, it.value5()!!) }, it.value7(), it.value8(), it.value9())
			}
		val lastRun = latest.mapNotNull { it.startedAt }.maxOrNull()

		val activity = dsl.select(AUDIT_EVENT.AT, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, AUDIT_EVENT.ACTION, AUDIT_EVENT.AFTER)
			.from(AUDIT_EVENT).leftJoin(PERSON).on(PERSON.ID.eq(AUDIT_EVENT.ACTOR_PERSON_ID))
			.where(AUDIT_EVENT.TARGET_TYPE.eq("project"), AUDIT_EVENT.TARGET_ID.eq(id))
			.orderBy(AUDIT_EVENT.AT.desc()).limit(20)
			.fetch { ActivityRow(it.value1()!!, it.value2()?.let { f -> name(it.value4(), f, it.value3()!!) }, it.value5()!!, it.value6()?.data()?.let(::detailOf)) }

		return ProjectDetail(
			id, project.name!!, project.slug!!, project.status!!, project.type, project.tags!!.filterNotNull(), project.termId, term, partner,
			project.startsOn, project.endsOn, members, resources,
			agreementsSigned = members.count { it.agreementStatus != "pending" },
			notion = notionPlan(chapterId, chapterCode, project, term),
			preview = preview,
			planned = planned,
			lastDryRunAt = lastRun,
			plannedOutOfDate = lastRun == null || project.updatedAt!!.isAfter(lastRun),
			activity = activity,
			canEdit = canEdit,
		)
	}

	private fun notionPlan(chapterId: UUID, chapterCode: String, project: ProjectRecord, term: String?): NotionPlan {
		val settings = dsl.select(CHAPTER.NOTION_DEFAULT_PARENT_ID, CHAPTER.NOTION_TITLE_PATTERN).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle()
		val title = NotionRoutes.title(settings.value2()!!, project.name!!, term, chapterCode)
		project.notionPageId?.let { return NotionPlan("has_page", title, it, null, null, null) }
		val routes = notion.routes(chapterId)
		if (routes.isEmpty() && settings.value1() == null) return NotionPlan("not_set_up", title, null, null, null, null)
		val placement = NotionRoutes.place(project.tags!!.filterNotNull(), project.type, routes, settings.value1())
		val parent = placement.parentPageId ?: return NotionPlan("no_route", title, null, null, null, null)
		return NotionPlan("planned", title, null, parent, notion.checks(listOf(parent))[parent]?.path, placement.matchedBy)
	}

	fun roles(): List<ProjectRoleOption> =
		dsl.selectFrom(PROJECT_ROLE).orderBy(PROJECT_ROLE.IS_LEAD.desc(), PROJECT_ROLE.NAME).fetch { ProjectRoleOption(it.id!!, it.name!!, it.isLead!!) }

	fun terms(chapterId: UUID): List<TermOption> =
		dsl.select(TERM.ID, TERM.SEASON, TERM.YEAR).from(TERM).join(CHAPTER).on(CHAPTER.INSTITUTION_ID.eq(TERM.INSTITUTION_ID))
			.where(CHAPTER.ID.eq(chapterId)).orderBy(TERM.STARTS_ON.desc())
			.fetch { TermOption(it.value1()!!, termLabel(it.value2(), it.value3()?.toInt())!!) }

	/** The chapter's resources, for attaching one to another project (PRD: resources can be shared across projects). */
	fun chapterResources(chapterId: UUID): List<ChapterResourceOption> {
		val projects = dsl.select(PROJECT_RESOURCE.RESOURCE_ID, PROJECT.NAME).from(PROJECT_RESOURCE).join(PROJECT).on(PROJECT.ID.eq(PROJECT_RESOURCE.PROJECT_ID))
			.where(PROJECT.CHAPTER_ID.eq(chapterId)).fetch().groupBy({ it.value1()!! }, { it.value2()!! })
		return dsl.selectFrom(RESOURCE).where(RESOURCE.CHAPTER_ID.eq(chapterId), RESOURCE.ARCHIVED_AT.isNull, RESOURCE.MANAGED.ne("unmanaged"))
			.orderBy(RESOURCE.TOOL, RESOURCE.NAME)
			.fetch { ChapterResourceOption(it.id!!, it.tool!!, it.name!!, if (it.externalId == null) "to_create" else "exists", projects[it.id].orEmpty().sorted()) }
	}

	companion object {
		fun name(preferred: String?, first: String, last: String) = "${preferred?.takeIf { it.isNotBlank() } ?: first} $last"

		fun termLabel(season: String?, year: Int?): String? = if (season == null || year == null) null else "${season.replaceFirstChar { it.uppercase() }} $year"

		/** The audit event's "summary" field, if it has one. */
		private val json = JsonMapper.builder().build()
		private fun detailOf(text: String): String? = json.readTree(text).path("summary").takeIf { it.isString }?.asString()
	}
}
