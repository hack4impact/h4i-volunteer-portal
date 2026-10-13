package org.hack4impact.portal.rules

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.records.RuleRecord
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.RULE
import org.hack4impact.portal.drafts.AccessDiff
import org.hack4impact.portal.projects.ProjectQueries
import org.hack4impact.portal.resolver.Reason
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.sync.WorldLoader
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Automation rules (build plan step 14; PRD automation rules). A chapter's rules reach only its people and resources;
 * national defaults ([chapterId] null) apply everywhere. Rules only add access (decision 10); the resolver's guards
 * (chapter scope, agreement gates, applicants and removed people) hold whatever a rule says (decision 51).
 */
@Component
class Rules(
	private val dsl: DSLContext,
	private val worlds: WorldLoader,
	private val diffs: AccessDiff,
	private val transactions: PlatformTransactionManager,
	private val mapper: JsonMapper,
) {
	companion object {
		val TOOLS = listOf("slack", "github", "google", "notion", "vaultwarden")
		private val TOOL_NAME = mapOf("slack" to "Slack", "github" to "GitHub", "google" to "Google", "notion" to "Notion", "vaultwarden" to "Vaultwarden")
		private val STATUSES = setOf("active", "alumni", "removal_requested")
		private val ROLES = mapOf("lead" to "Chapter leads", "co_lead" to "co-leads", "viewer" to "viewers")
		private fun bad(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
		private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such rule")
		private fun scope(chapterId: UUID?) = if (chapterId == null) RULE.CHAPTER_ID.isNull else RULE.CHAPTER_ID.eq(chapterId)
	}

	// ---- Reading --------------------------------------------------------------------------------------------------

	fun page(chapterId: UUID?, canEdit: Boolean): RulesPage {
		val reach = reach()
		val names = Names.load(dsl)
		fun views(of: UUID?) = dsl.select(RULE.asterisk(), PERSON.FIRST_NAME, PERSON.LAST_NAME).from(RULE).leftJoin(PERSON).on(PERSON.ID.eq(DSL.coalesce(RULE.UPDATED_BY, RULE.CREATED_BY)))
			.where(scope(of), RULE.DELETED_AT.isNull).orderBy(RULE.CREATED_AT)
			.fetch { r -> view(r.into(RULE), r.get(PERSON.FIRST_NAME)?.let { "$it ${r.get(PERSON.LAST_NAME)}" }, reach, names) }
		return RulesPage(if (chapterId == null) views(null) else views(chapterId), if (chapterId == null) emptyList() else views(null), canEdit, options(chapterId))
	}

	private fun view(r: RuleRecord, by: String?, reach: Map<UUID, RuleReach>, names: Names): RuleView {
		val input = input(r)
		val code = r.chapterId?.let { dsl.select(CHAPTER.CODE).from(CHAPTER).where(CHAPTER.ID.eq(it)).fetchOne()?.value1() }
		return RuleView(r.id!!, code, r.name!!, r.description, r.enabled!!, input, sentence(input, names), reach[r.id] ?: RuleReach(0, 0), by,
			r.updatedAt ?: r.createdAt!!, warnings(r.chapterId, input))
	}

	/** People and resources each rule reaches now: grants whose reasons name the rule. */
	private fun reach(): Map<UUID, RuleReach> {
		val grants = Resolver.resolve(worlds.load().world).grants
		val byRule = mutableMapOf<UUID, MutableList<Pair<UUID, UUID>>>()
		for (g in grants) for (reason in g.reasons) {
			val id = when (reason) { is Reason.FromRule -> reason.ruleId; is Reason.NationalDefault -> reason.ruleId; else -> null } ?: continue
			byRule.getOrPut(id) { mutableListOf() } += g.personId to g.resourceId
		}
		return byRule.mapValues { (_, pairs) -> RuleReach(pairs.map { it.first }.toSet().size, pairs.map { it.second }.toSet().size) }
	}

	fun input(r: RuleRecord): RuleInput {
		val who = mapper.readTree(r.who!!.data())
		val projects = mapper.readTree(r.projects!!.data())
		val resources = mapper.readTree(r.resources!!.data())
		fun strings(n: JsonNode, f: String) = n.path(f).toList().map { it.asString() }
		return RuleInput(
			r.name!!, r.description,
			RuleWho(strings(who, "statuses").ifEmpty { listOf("active") }.map { it.lowercase() }, who.path("everyone").asBoolean(false),
				strings(who, "chapterRoles").map { it.lowercase() }, strings(who, "titles"), strings(who, "projectRoles").map(UUID::fromString), strings(who, "people").map(UUID::fromString)),
			when {
				projects.path("all").asBoolean(false) -> RuleProjects("all")
				projects.has("ids") -> RuleProjects("ids", ids = strings(projects, "ids").map(UUID::fromString))
				projects.has("tags") -> RuleProjects("tags", tags = strings(projects, "tags"))
				else -> RuleProjects("none")
			},
			if (resources.has("ids")) RuleResources("ids", ids = strings(resources, "ids").map(UUID::fromString)) else RuleResources("tags", tags = strings(resources, "tags")),
			r.tools.orEmpty().filterNotNull(), r.accessLevel!!.lowercase(), r.enabled!!,
		)
	}

	fun options(chapterId: UUID?): RuleOptions {
		val roles = dsl.selectFrom(PROJECT_ROLE).orderBy(PROJECT_ROLE.NAME).fetch { RuleOption(it.id!!, it.name!!, if (it.isLead == true) "lead role" else null) }
		val projectTags = dsl.select(PROJECT.TAGS).from(PROJECT).where(if (chapterId == null) DSL.noCondition() else PROJECT.CHAPTER_ID.eq(chapterId)).fetch().flatMap { it.value1().orEmpty().filterNotNull() }
		val resourceTags = dsl.select(RESOURCE.TAGS).from(RESOURCE).where(RESOURCE.ARCHIVED_AT.isNull, if (chapterId == null) DSL.noCondition() else RESOURCE.CHAPTER_ID.eq(chapterId))
			.fetch().flatMap { it.value1().orEmpty().filterNotNull() }
		if (chapterId == null) return RuleOptions(roles, emptyList(), emptyList(), emptyList(), (projectTags + resourceTags).distinct().sorted(), emptyList())
		return RuleOptions(
			roles,
			dsl.selectDistinct(CHAPTER_ROLE.TITLE).from(CHAPTER_ROLE).where(CHAPTER_ROLE.CHAPTER_ID.eq(chapterId), CHAPTER_ROLE.TITLE.isNotNull).fetch().mapNotNull { it.value1() }.sorted(),
			dsl.selectFrom(PROJECT).where(PROJECT.CHAPTER_ID.eq(chapterId), PROJECT.STATUS.ne("closed")).orderBy(PROJECT.NAME)
				.fetch { RuleOption(it.id!!, it.name!!, it.tags.orEmpty().filterNotNull().joinToString(" ") { t -> "#$t" }.ifEmpty { null }) },
			dsl.selectFrom(RESOURCE).where(RESOURCE.CHAPTER_ID.eq(chapterId), RESOURCE.ARCHIVED_AT.isNull, RESOURCE.MANAGED.ne("unmanaged")).orderBy(RESOURCE.TOOL, RESOURCE.NAME)
				.fetch { RuleOption(it.id!!, it.name!!, it.tags.orEmpty().filterNotNull().joinToString(" ") { t -> "#$t" }.ifEmpty { null }, it.tool) },
			(projectTags + resourceTags).distinct().sorted(),
			dsl.select(PERSON.ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, PERSON.STATUS).from(PERSON)
				.join(CHAPTER_MEMBERSHIP).on(CHAPTER_MEMBERSHIP.PERSON_ID.eq(PERSON.ID))
				.where(CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(chapterId), CHAPTER_MEMBERSHIP.LEFT_ON.isNull, PERSON.DELETED_AT.isNull, PERSON.STATUS.`in`(STATUSES))
				.fetch { RuleOption(it.value1()!!, ProjectQueries.name(it.value4(), it.value2()!!, it.value3()!!), it.value5().takeIf { s -> s != "active" }) }
				.sortedBy { it.name },
		)
	}

	// ---- The rule in words ------------------------------------------------------------------------------------------

	/** Names for IDs a rule refers to. */
	private class Names(val roles: Map<UUID, String>, val people: Map<UUID, String>, val projects: Map<UUID, String>, val resources: Map<UUID, String>) {
		companion object {
			fun load(dsl: DSLContext) = Names(
				dsl.select(PROJECT_ROLE.ID, PROJECT_ROLE.NAME).from(PROJECT_ROLE).fetch().associate { it.value1()!! to it.value2()!! },
				dsl.select(PERSON.ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME).from(PERSON).fetch().associate { it.value1()!! to ProjectQueries.name(it.value4(), it.value2()!!, it.value3()!!) },
				dsl.select(PROJECT.ID, PROJECT.NAME).from(PROJECT).fetch().associate { it.value1()!! to it.value2()!! },
				dsl.select(RESOURCE.ID, RESOURCE.TOOL, RESOURCE.NAME).from(RESOURCE).fetch().associate { it.value1()!! to (if (it.value2() == "slack") "#${it.value3()}" else it.value3()!!) },
			)
		}
	}

	fun sentence(input: RuleInput): String = sentence(input, Names.load(dsl))

	private fun sentence(i: RuleInput, n: Names): String {
		fun list(items: List<String>) = if (items.size <= 1) items.joinToString("") else items.dropLast(1).joinToString(", ") + " and " + items.last()
		val w = i.who
		val who = if (w.everyone) "Everyone" else list(buildList {
			w.chapterRoles.mapNotNull { ROLES[it] }.takeIf { it.isNotEmpty() }?.let { add(list(it)) }
			w.titles.forEach { add("“$it”") }
			w.projectRoles.mapNotNull { n.roles[it] }.forEach { add("${it}s on any project") }
			w.people.mapNotNull { n.people[it] }.takeIf { it.isNotEmpty() }?.let { add(list(it)) }
		}).ifEmpty { "Nobody yet" }
		val statuses = w.statuses.toSet()
		val whoStatus = when {
			statuses == setOf("active") -> ""
			"alumni" in statuses && "active" in statuses -> " (members and alumni)"
			statuses == setOf("alumni") -> " (alumni)"
			else -> ""
		}
		val what = when (i.resources.scope) {
			"ids" -> list(i.resources.ids.mapNotNull { n.resources[it] }).ifEmpty { "the chosen resources" }
			else -> "resources tagged ${i.resources.tags.joinToString(" ") { "#$it" }}"
		}
		val where = when (i.projects.scope) {
			"all" -> " in every live project"
			"ids" -> " in ${list(i.projects.ids.mapNotNull { n.projects[it] })}"
			"tags" -> " in projects tagged ${i.projects.tags.joinToString(" ") { "#$it" }}"
			else -> ""
		}
		val tools = if (i.tools.isEmpty()) "" else " · only ${list(i.tools.mapNotNull { TOOL_NAME[it] })}"
		return "$who$whoStatus → ${i.access.replaceFirstChar { it.uppercase() }} on $what$where$tools"
	}

	// ---- Checking ---------------------------------------------------------------------------------------------------

	/** Refuses what can't work, in words (400). */
	private fun validate(chapterId: UUID?, i: RuleInput) {
		if (i.name.isBlank()) bad("Give the rule a name, e.g. “Tech leads get the repos”")
		if (i.access !in setOf("read", "write", "admin")) bad("Access is read, write or admin")
		if (i.tools.any { it !in TOOLS }) bad("Unknown tool: ${i.tools.first { it !in TOOLS }}")
		val w = i.who
		if (w.statuses.isEmpty() || w.statuses.any { it !in STATUSES }) bad("Statuses are active, alumni or removal_requested")
		if (w.chapterRoles.any { it !in ROLES }) bad("Chapter roles are lead, co_lead or viewer")
		if (!w.everyone && w.chapterRoles.isEmpty() && w.titles.isEmpty() && w.projectRoles.isEmpty() && w.people.isEmpty()) bad("Say who it's for: everyone, a role, a title or people")
		if (w.projectRoles.isNotEmpty() && dsl.fetchCount(PROJECT_ROLE, PROJECT_ROLE.ID.`in`(w.projectRoles)) != w.projectRoles.toSet().size) bad("One of those project roles doesn't exist")
		if (chapterId != null && w.people.isNotEmpty()) {
			val members = dsl.select(CHAPTER_MEMBERSHIP.PERSON_ID).from(CHAPTER_MEMBERSHIP).where(CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(chapterId), CHAPTER_MEMBERSHIP.PERSON_ID.`in`(w.people)).fetch().map { it.value1() }.toSet()
			if (members.size != w.people.toSet().size) bad("Everyone listed must be a member of this chapter")
		}
		if (chapterId == null && (w.people.isNotEmpty() || w.titles.isNotEmpty())) bad("National defaults go by roles or everyone, not by name or title")
		when (i.projects.scope) {
			"none", "all" -> Unit
			"tags" -> if (i.projects.tags.none { it.isNotBlank() }) bad("Which project tags?")
			"ids" -> {
				if (chapterId == null) bad("National defaults pick projects by tag, not one by one")
				if (i.projects.ids.isEmpty()) bad("Pick at least one project")
				if (dsl.fetchCount(PROJECT, PROJECT.ID.`in`(i.projects.ids), PROJECT.CHAPTER_ID.eq(chapterId)) != i.projects.ids.toSet().size) bad("Only this chapter's projects")
			}
			else -> bad("Projects: none, all, ids or tags")
		}
		when (i.resources.scope) {
			"tags" -> if (i.resources.tags.none { it.isNotBlank() }) bad("Which resource tags?")
			"ids" -> {
				if (chapterId == null) bad("National defaults pick resources by tag, not one by one")
				if (i.resources.ids.isEmpty()) bad("Pick at least one resource")
				if (dsl.fetchCount(RESOURCE, RESOURCE.ID.`in`(i.resources.ids), RESOURCE.CHAPTER_ID.eq(chapterId), RESOURCE.ARCHIVED_AT.isNull) != i.resources.ids.toSet().size) bad("Only this chapter's resources, and not archived ones")
			}
			else -> bad("Resources: ids or tags")
		}
	}

	/** Worth knowing, not wrong. */
	private fun warnings(chapterId: UUID?, i: RuleInput): List<String> = buildList {
		if (i.resources.scope == "tags") {
			val tags = dsl.select(RESOURCE.TAGS).from(RESOURCE).where(RESOURCE.ARCHIVED_AT.isNull, if (chapterId == null) DSL.noCondition() else RESOURCE.CHAPTER_ID.eq(chapterId))
				.fetch().flatMap { it.value1().orEmpty().filterNotNull() }.toSet()
			i.resources.tags.filter { it.isNotBlank() && it.lowercase() !in tags }.forEach { add("No resource is tagged #$it yet; the rule will cover ones that are") }
		}
		if (i.projects.scope == "tags") {
			val tags = dsl.select(PROJECT.TAGS).from(PROJECT).where(if (chapterId == null) DSL.noCondition() else PROJECT.CHAPTER_ID.eq(chapterId))
				.fetch().flatMap { it.value1().orEmpty().filterNotNull() }.toSet()
			i.projects.tags.filter { it.isNotBlank() && it.lowercase() !in tags }.forEach { add("No project is tagged #$it yet") }
		}
		// GitHub (maintainer), Google groups (manager) and Vaultwarden (manage) have an admin level; these don't.
		if (i.access == "admin" && (i.tools.isEmpty() || i.tools.any { it in setOf("slack", "notion") })) {
			add("Slack and Notion have no admin level: there people just get added")
		}
	}

	// ---- Changing ----------------------------------------------------------------------------------------------------

	private fun normalized(i: RuleInput) = i.copy(
		name = i.name.trim(), description = i.description?.trim()?.ifEmpty { null },
		who = i.who.copy(titles = i.who.titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct(), statuses = i.who.statuses.distinct()),
		projects = i.projects.copy(tags = i.projects.tags.map { it.trim().lowercase().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()),
		resources = i.resources.copy(tags = i.resources.tags.map { it.trim().lowercase().removePrefix("#") }.filter { it.isNotEmpty() }.distinct()),
		tools = i.tools.distinct(),
	)

	private fun json(i: RuleInput): Triple<JSONB, JSONB, JSONB> {
		val who = buildMap<String, Any> {
			put("statuses", i.who.statuses)
			if (i.who.everyone) put("everyone", true)
			if (i.who.chapterRoles.isNotEmpty()) put("chapterRoles", i.who.chapterRoles)
			if (i.who.titles.isNotEmpty()) put("titles", i.who.titles)
			if (i.who.projectRoles.isNotEmpty()) put("projectRoles", i.who.projectRoles)
			if (i.who.people.isNotEmpty()) put("people", i.who.people)
		}
		val projects: Map<String, Any> = when (i.projects.scope) { "all" -> mapOf("all" to true); "ids" -> mapOf("ids" to i.projects.ids); "tags" -> mapOf("tags" to i.projects.tags); else -> emptyMap() }
		val resources: Map<String, Any> = if (i.resources.scope == "ids") mapOf("ids" to i.resources.ids) else mapOf("tags" to i.resources.tags)
		fun j(v: Any) = JSONB.valueOf(mapper.writeValueAsString(v))
		return Triple(j(who), j(projects), j(resources))
	}

	/** [id]: chosen by a draft, so its later changes can refer to the rule. */
	fun create(chapterId: UUID?, raw: RuleInput, viewer: Viewer, id: UUID? = null): UUID {
		val i = normalized(raw)
		validate(chapterId, i)
		val (who, projects, resources) = json(i)
		val ruleId = dsl.insertInto(RULE).set(RULE.ID, id ?: UUID.randomUUID()).set(RULE.CHAPTER_ID, chapterId).set(RULE.NAME, i.name).set(RULE.DESCRIPTION, i.description)
			.set(RULE.ENABLED, i.enabled).set(RULE.WHO, who).set(RULE.PROJECTS, projects).set(RULE.RESOURCES, resources).set(RULE.TOOLS, i.tools.toTypedArray())
			.set(RULE.ACCESS_LEVEL, i.access).set(RULE.CREATED_BY, viewer.personId).set(RULE.UPDATED_BY, viewer.personId)
			.returningResult(RULE.ID).fetchSingle().value1()!!
		audit(viewer, chapterId, ruleId, "rule.create", null, i)
		return ruleId
	}

	fun update(chapterId: UUID?, id: UUID, raw: RuleInput, viewer: Viewer) {
		val before = record(chapterId, id)
		val i = normalized(raw)
		validate(chapterId, i)
		val (who, projects, resources) = json(i)
		dsl.update(RULE).set(RULE.NAME, i.name).set(RULE.DESCRIPTION, i.description).set(RULE.ENABLED, i.enabled).set(RULE.WHO, who).set(RULE.PROJECTS, projects)
			.set(RULE.RESOURCES, resources).set(RULE.TOOLS, i.tools.toTypedArray()).set(RULE.ACCESS_LEVEL, i.access).set(RULE.UPDATED_BY, viewer.personId)
			.set(RULE.UPDATED_AT, OffsetDateTime.now()).where(RULE.ID.eq(id)).execute()
		audit(viewer, chapterId, id, "rule.update", input(before), i)
	}

	fun setEnabled(chapterId: UUID?, id: UUID, enabled: Boolean, viewer: Viewer) {
		val before = record(chapterId, id)
		dsl.update(RULE).set(RULE.ENABLED, enabled).set(RULE.UPDATED_BY, viewer.personId).set(RULE.UPDATED_AT, OffsetDateTime.now()).where(RULE.ID.eq(id)).execute()
		audit(viewer, chapterId, id, if (enabled) "rule.enable" else "rule.disable", input(before), input(before).copy(enabled = enabled))
	}

	/** Soft delete: sync records and the audit log keep pointing at it. */
	fun delete(chapterId: UUID?, id: UUID, viewer: Viewer) {
		val before = record(chapterId, id)
		dsl.update(RULE).set(RULE.DELETED_AT, OffsetDateTime.now()).set(RULE.UPDATED_BY, viewer.personId).where(RULE.ID.eq(id)).execute()
		audit(viewer, chapterId, id, "rule.delete", input(before), null)
	}

	fun record(chapterId: UUID?, id: UUID): RuleRecord =
		dsl.selectFrom(RULE).where(RULE.ID.eq(id), scope(chapterId), RULE.DELETED_AT.isNull).fetchOne() ?: notFound()

	fun name(id: UUID): String = dsl.select(RULE.NAME).from(RULE).where(RULE.ID.eq(id)).fetchOne()?.value1() ?: "a rule"

	/** A change and its sync request together: the sync runs after the change commits (outbox). */
	fun inTransaction(block: () -> Unit) = TransactionTemplate(transactions).executeWithoutResult { block() }

	/** What a change would do, before it's saved: applied in a transaction that's rolled back, then compared. */
	fun preview(chapterId: UUID?, p: RulePreviewInput, viewer: Viewer): RulePreview {
		var result: RulePreview? = null
		TransactionTemplate(transactions).executeWithoutResult { s ->
			try {
				val before = diffs.snapshot()
				when {
					p.delete && p.ruleId != null -> delete(chapterId, p.ruleId, viewer)
					p.ruleId != null && p.input != null -> update(chapterId, p.ruleId, p.input, viewer)
					p.input != null -> create(chapterId, p.input, viewer)
					else -> bad("Nothing to preview")
				}
				result = RulePreview(p.input?.let { sentence(normalized(it)) }, diffs.diff(before, diffs.snapshot()), p.input?.let { warnings(chapterId, normalized(it)) }.orEmpty())
			} finally {
				s.setRollbackOnly()
			}
		}
		return result!!
	}

	private fun audit(viewer: Viewer, chapterId: UUID?, id: UUID, action: String, before: RuleInput?, after: RuleInput?) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "rule").set(AUDIT_EVENT.TARGET_ID, id).set(AUDIT_EVENT.CHAPTER_ID, chapterId)
			.set(AUDIT_EVENT.BEFORE, before?.let { JSONB.valueOf(mapper.writeValueAsString(it)) })
			.set(AUDIT_EVENT.AFTER, after?.let { JSONB.valueOf(mapper.writeValueAsString(it + ("summary" to sentence(it)))) })
			.execute()
	}

	private operator fun RuleInput.plus(extra: Pair<String, String>): Map<String, Any?> =
		mapper.convertValue(this, Map::class.java).let {
			@Suppress("UNCHECKED_CAST")
			val map = it as Map<String, Any?>
			map + extra
		}
}
