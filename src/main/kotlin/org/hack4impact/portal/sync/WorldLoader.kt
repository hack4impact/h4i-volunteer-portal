package org.hack4impact.portal.sync

import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_RESOURCE
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.RULE
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Audience
import org.hack4impact.portal.resolver.ChapterAudience
import org.hack4impact.portal.resolver.ChapterResource
import org.hack4impact.portal.resolver.ChapterRole
import org.hack4impact.portal.resolver.ChapterRoleAssignment
import org.hack4impact.portal.resolver.Person
import org.hack4impact.portal.resolver.PersonStatus
import org.hack4impact.portal.resolver.Project
import org.hack4impact.portal.resolver.ProjectMember
import org.hack4impact.portal.resolver.ProjectResource
import org.hack4impact.portal.resolver.ProjectRole
import org.hack4impact.portal.resolver.ProjectScope
import org.hack4impact.portal.resolver.ProjectStatus
import org.hack4impact.portal.resolver.Resource
import org.hack4impact.portal.resolver.ResourceScope
import org.hack4impact.portal.resolver.Rule
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.resolver.Who
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** The resolver's input built from the portal's tables, plus anything that couldn't be read. */
data class LoadedWorld(val world: org.hack4impact.portal.resolver.World, val warnings: List<String>)

/**
 * Reads released data into the resolver's [World] (build plan step 6). Only current rows count: live chapter
 * memberships, roles without an end, members not removed. A malformed rule or access level is skipped with a
 * warning rather than failing the whole sync.
 */
@Component
class WorldLoader(private val dsl: DSLContext) {
	private val json = JsonMapper.builder().build()

	fun load(): LoadedWorld {
		val warnings = mutableListOf<String>()

		val memberships = dsl.select(CHAPTER_MEMBERSHIP.PERSON_ID, CHAPTER_MEMBERSHIP.CHAPTER_ID).from(CHAPTER_MEMBERSHIP)
			.join(CHAPTER).on(CHAPTER.ID.eq(CHAPTER_MEMBERSHIP.CHAPTER_ID))
			.where(CHAPTER_MEMBERSHIP.LEFT_ON.isNull, CHAPTER.DELETED_AT.isNull)
			.fetch().groupBy({ it.value1()!! }, { it.value2()!! })
		val roles = dsl.select(CHAPTER_ROLE.PERSON_ID, CHAPTER_ROLE.CHAPTER_ID, CHAPTER_ROLE.ROLE, CHAPTER_ROLE.TITLE).from(CHAPTER_ROLE)
			.where(CHAPTER_ROLE.ENDS_AT.isNull)
			.fetch().groupBy({ it.value1()!! }, { ChapterRoleAssignment(it.value2()!!, ChapterRole.valueOf(it.value3()!!.uppercase()), it.value4()) })
		val people = dsl.select(PERSON.ID, PERSON.STATUS).from(PERSON).where(PERSON.DELETED_AT.isNull).fetch {
			val id = it.value1()!!
			Person(id, PersonStatus.valueOf(it.value2()!!.uppercase()), memberships[id].orEmpty().toSet(), roles[id].orEmpty().toSet())
		}

		val projects = dsl.select(PROJECT.ID, PROJECT.CHAPTER_ID, PROJECT.STATUS, PROJECT.TAGS).from(PROJECT).fetch {
			Project(it.value1()!!, it.value2()!!, ProjectStatus.valueOf(it.value3()!!.uppercase()), it.value4().orEmpty().filterNotNull().toSet())
		}
		val projectRoles = dsl.select(PROJECT_ROLE.ID, PROJECT_ROLE.IS_LEAD).from(PROJECT_ROLE).fetch { ProjectRole(it.value1()!!, it.value2()!!) }
		val members = dsl.select(PROJECT_MEMBER.PROJECT_ID, PROJECT_MEMBER.PERSON_ID, PROJECT_MEMBER.PROJECT_ROLE_ID, PROJECT_MEMBER.AGREEMENT_STATUS)
			.from(PROJECT_MEMBER).where(PROJECT_MEMBER.REMOVED_AT.isNull)
			// "waived": imported members who predate portal agreements.
			.fetch { ProjectMember(it.value1()!!, it.value2()!!, it.value3(), it.value4() in setOf("signed", "waived")) }

		val resources = dsl.select(RESOURCE.ID, RESOURCE.TOOL, RESOURCE.CHAPTER_ID, RESOURCE.TAGS, RESOURCE.ARCHIVED_AT).from(RESOURCE).fetch {
			Resource(it.value1()!!, Tool.valueOf(it.value2()!!.uppercase()), it.value3(), it.value4().orEmpty().filterNotNull().toSet(), it.value5() != null)
		}
		val projectResources = dsl.selectFrom(PROJECT_RESOURCE).fetch().mapNotNull { pr ->
			val access = access(pr.accessLevel, "project resource ${pr.projectId}/${pr.resourceId}", warnings) ?: return@mapNotNull null
			val audience = when (pr.audience) {
				"team" -> Audience.Team
				"leads" -> Audience.Leads
				else -> Audience.Role(pr.audienceRoleId!!)
			}
			ProjectResource(pr.projectId!!, pr.resourceId!!, audience, access, pr.requiresAgreement == true)
		}
		val chapterResources = dsl.selectFrom(CHAPTER_RESOURCE).fetch().mapNotNull { cr ->
			val access = access(cr.accessLevel, "chapter resource ${cr.chapterId}/${cr.resourceId}", warnings) ?: return@mapNotNull null
			ChapterResource(cr.chapterId!!, cr.resourceId!!, ChapterAudience.valueOf(cr.audience!!.uppercase()), access)
		}
		val rules = dsl.selectFrom(RULE).fetch().mapNotNull { r ->
			try {
				Rule(
					id = r.id!!,
					chapterId = r.chapterId,
					who = who(json.readTree(r.who!!.data())),
					projects = projectScope(json.readTree(r.projects!!.data())),
					resources = resourceScope(json.readTree(r.resources!!.data())),
					access = access(r.accessLevel, "rule ${r.name}", warnings) ?: return@mapNotNull null,
					tools = r.tools.orEmpty().filterNotNull().map { Tool.valueOf(it.uppercase()) }.toSet(),
					enabled = r.enabled == true,
				)
			} catch (e: RuntimeException) {
				warnings += "rule '${r.name}' skipped: ${e.message}"
				null
			}
		}
		return LoadedWorld(org.hack4impact.portal.resolver.World(people, projects, projectRoles, members, resources, projectResources, chapterResources, rules), warnings)
	}

	private fun access(value: String?, what: String, warnings: MutableList<String>): Access? =
		Access.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: run {
			warnings += "$what skipped: unknown access level '$value' (use read, write or admin)"
			null
		}

	// Rule JSON (see V1 migration): who {statuses, everyone, chapterRoles, titles, projectRoles, people},
	// projects {none | all | ids | tags}, resources {ids | tags}.
	private fun who(node: JsonNode) = Who(
		statuses = strings(node, "statuses").map { PersonStatus.valueOf(it.uppercase()) }.toSet().ifEmpty { setOf(PersonStatus.ACTIVE) },
		everyone = node.path("everyone").asBoolean(false),
		chapterRoles = strings(node, "chapterRoles").map { ChapterRole.valueOf(it.uppercase()) }.toSet(),
		titles = strings(node, "titles").toSet(),
		projectRoles = strings(node, "projectRoles").map(UUID::fromString).toSet(),
		people = strings(node, "people").map(UUID::fromString).toSet(),
	)

	private fun projectScope(node: JsonNode): ProjectScope = when {
		node.path("all").asBoolean(false) -> ProjectScope.All
		node.has("ids") -> ProjectScope.Ids(strings(node, "ids").map(UUID::fromString).toSet())
		node.has("tags") -> ProjectScope.Tags(strings(node, "tags").toSet())
		else -> ProjectScope.None
	}

	private fun resourceScope(node: JsonNode): ResourceScope = when {
		node.has("ids") -> ResourceScope.Ids(strings(node, "ids").map(UUID::fromString).toSet())
		node.has("tags") -> ResourceScope.Tags(strings(node, "tags").toSet())
		else -> error("resources needs ids or tags")
	}

	private fun strings(node: JsonNode, field: String): List<String> = buildList { node.path(field).forEach { add(it.asString()) } }
}
