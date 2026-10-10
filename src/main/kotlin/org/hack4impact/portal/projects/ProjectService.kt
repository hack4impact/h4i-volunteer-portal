package org.hack4impact.portal.projects

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.records.ProjectRecord
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_RESOURCE
import org.hack4impact.portal.db.tables.references.PARTNER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE_TEMPLATE
import org.hack4impact.portal.db.tables.references.TERM
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Changes to projects (build plan step 8). Until drafts exist (step 12) changes apply directly; every tool is still
 * in dry run, so they only change what the next dry run plans. Every change is audited on the project.
 */
@Component
class ProjectService(private val dsl: DSLContext) {
	private val json = JsonMapper.builder().build()

	companion object {
		val TRANSITIONS = mapOf(
			"draft" to setOf("active", "closed"),
			"active" to setOf("paused", "closed"),
			"paused" to setOf("active", "closed"),
			"closed" to emptySet(),
		)
		private val SLUG = Regex("^[a-z0-9][a-z0-9-]*$")
		private val NAME_RULES = mapOf(
			"slack" to (Regex("^[a-z0-9][a-z0-9_-]{0,79}$") to "lowercase letters, digits, - and _, at most 80"),
			"github" to (Regex("^[a-z0-9][a-z0-9-]{0,99}$") to "lowercase letters, digits and -"),
			"google" to (Regex("^[a-z0-9][a-z0-9._-]*@([a-z0-9-]+\\.)*hack4impact\\.org$") to "an address at hack4impact.org"),
			"vaultwarden" to (Regex("^.{1,100}$") to "at most 100 characters"),
		)

		fun slugify(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

		/** A resource name as the tool will need it: Slack names lose a leading # and are lowercase, GitHub team slugs too. */
		fun normalizeName(tool: String, name: String) = when (tool) {
			"slack", "github", "google" -> name.trim().trimStart('#').lowercase()
			else -> name.trim()
		}

		private fun bad(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
		private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
		private fun tags(values: List<String>) = values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().sorted()
	}

	@Transactional
	fun create(chapterId: UUID, input: ProjectInput, viewer: Viewer): ProjectRecord {
		val name = input.name.trim().ifEmpty { bad("A project needs a name") }
		val slug = (input.slug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: slugify(name))
		if (!SLUG.matches(slug)) bad("The short name may only have lowercase letters, digits and dashes")
		if (dsl.fetchExists(PROJECT, PROJECT.CHAPTER_ID.eq(chapterId), PROJECT.SLUG.eq(slug))) conflict("This chapter already has a project called $slug")
		checkTerm(chapterId, input.termId)
		val project = dsl.insertInto(PROJECT)
			.set(PROJECT.CHAPTER_ID, chapterId).set(PROJECT.NAME, name).set(PROJECT.SLUG, slug)
			.set(PROJECT.TYPE, input.type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }).set(PROJECT.TAGS, tags(input.tags).toTypedArray())
			.set(PROJECT.TERM_ID, input.termId).set(PROJECT.PARTNER_ID, partner(input.partner))
			.set(PROJECT.STARTS_ON, input.startsOn).set(PROJECT.ENDS_ON, input.endsOn)
			.returning().fetchSingle()

		// The standard resources (PRD), from the chapter's template or else the national one.
		val code = dsl.select(CHAPTER.CODE).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle().value1()!!
		val own = dsl.fetchExists(RESOURCE_TEMPLATE, RESOURCE_TEMPLATE.CHAPTER_ID.eq(chapterId))
		val templates = dsl.selectFrom(RESOURCE_TEMPLATE)
			.where(if (own) RESOURCE_TEMPLATE.CHAPTER_ID.eq(chapterId) else RESOURCE_TEMPLATE.CHAPTER_ID.isNull)
			.orderBy(RESOURCE_TEMPLATE.POSITION).fetch()
		for (t in templates) {
			val resourceName = normalizeName(t.tool!!, t.namePattern!!.replace("{chapter}", code).replace("{project}", slug))
			val resource = existing(chapterId, t.tool!!, resourceName) ?: insertResource(chapterId, t.tool!!, resourceName, t.tags!!.filterNotNull())
			dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project.id).set(PROJECT_RESOURCE.RESOURCE_ID, resource)
				.set(PROJECT_RESOURCE.AUDIENCE, t.audience).set(PROJECT_RESOURCE.ACCESS_LEVEL, t.accessLevel)
				.set(PROJECT_RESOURCE.REQUIRES_AGREEMENT, t.requiresAgreement)
				.onConflictDoNothing().execute()
		}
		audit(viewer, chapterId, project.id!!, "project.create", "Created $name with ${templates.size} standard resources")
		return project
	}

	@Transactional
	fun update(chapterId: UUID, project: ProjectRecord, input: ProjectInput, viewer: Viewer) {
		val name = input.name.trim().ifEmpty { bad("A project needs a name") }
		checkTerm(chapterId, input.termId)
		if (input.startsOn != null && input.endsOn != null && input.endsOn.isBefore(input.startsOn)) bad("The end date is before the start date")
		dsl.update(PROJECT)
			.set(PROJECT.NAME, name).set(PROJECT.TYPE, input.type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() })
			.set(PROJECT.TAGS, tags(input.tags).toTypedArray()).set(PROJECT.TERM_ID, input.termId).set(PROJECT.PARTNER_ID, partner(input.partner))
			.set(PROJECT.STARTS_ON, input.startsOn).set(PROJECT.ENDS_ON, input.endsOn)
			.where(PROJECT.ID.eq(project.id)).execute()
		audit(viewer, chapterId, project.id!!, "project.update", "Edited the project details")
	}

	@Transactional
	fun setStatus(chapterId: UUID, project: ProjectRecord, to: String, viewer: Viewer) {
		val from = project.status!!
		if (to !in TRANSITIONS.getValue(from)) conflict("A $from project can't become $to")
		dsl.update(PROJECT).set(PROJECT.STATUS, to).set(PROJECT.CLOSED_AT, if (to == "closed") OffsetDateTime.now() else null)
			.where(PROJECT.ID.eq(project.id)).execute()
		audit(viewer, chapterId, project.id!!, "project.status", "Changed status from $from to $to")
	}

	@Transactional
	fun addMember(chapterId: UUID, project: ProjectRecord, input: MemberInput, viewer: Viewer) {
		val person = dsl.select(PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME, PERSON.STATUS).from(PERSON)
			.join(CHAPTER_MEMBERSHIP).on(CHAPTER_MEMBERSHIP.PERSON_ID.eq(PERSON.ID))
			.where(PERSON.ID.eq(input.personId), PERSON.DELETED_AT.isNull, CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(chapterId), CHAPTER_MEMBERSHIP.LEFT_ON.isNull)
			.fetchOne() ?: bad("That person isn't a member of this chapter")
		// PRD: alumni can be added like any member. Applicants and removed people can't.
		if (person.value4() !in setOf("active", "alumni", "removal_requested")) bad("Only active members and alumni can join a project")
		checkRole(input.roleId)
		if (dsl.fetchExists(PROJECT_MEMBER, PROJECT_MEMBER.PROJECT_ID.eq(project.id), PROJECT_MEMBER.PERSON_ID.eq(input.personId), PROJECT_MEMBER.REMOVED_AT.isNull)) {
			conflict("Already on this project")
		}
		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project.id).set(PROJECT_MEMBER.PERSON_ID, input.personId)
			.set(PROJECT_MEMBER.PROJECT_ROLE_ID, input.roleId).set(PROJECT_MEMBER.ADDED_BY, viewer.personId).execute()
		touch(project)
		audit(viewer, chapterId, project.id!!, "project.member_add", "Added ${ProjectQueries.name(person.value3(), person.value1()!!, person.value2()!!)}")
	}

	@Transactional
	fun setMemberRole(chapterId: UUID, project: ProjectRecord, personId: UUID, input: MemberRoleInput, viewer: Viewer) {
		checkRole(input.roleId)
		val updated = dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ROLE_ID, input.roleId)
			.where(PROJECT_MEMBER.PROJECT_ID.eq(project.id), PROJECT_MEMBER.PERSON_ID.eq(personId), PROJECT_MEMBER.REMOVED_AT.isNull).execute()
		if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not on this project")
		touch(project)
		audit(viewer, chapterId, project.id!!, "project.member_role", "Changed ${personName(personId)}'s role")
	}

	@Transactional
	fun removeMember(chapterId: UUID, project: ProjectRecord, personId: UUID, viewer: Viewer) {
		val updated = dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now())
			.where(PROJECT_MEMBER.PROJECT_ID.eq(project.id), PROJECT_MEMBER.PERSON_ID.eq(personId), PROJECT_MEMBER.REMOVED_AT.isNull).execute()
		if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not on this project")
		touch(project)
		audit(viewer, chapterId, project.id!!, "project.member_remove", "Removed ${personName(personId)}")
	}

	@Transactional
	fun attachResource(chapterId: UUID, project: ProjectRecord, input: ResourceInput, viewer: Viewer): UUID {
		checkAudience(input)
		val resourceId = if (input.resourceId != null) {
			val r = dsl.selectFrom(RESOURCE).where(RESOURCE.ID.eq(input.resourceId), RESOURCE.CHAPTER_ID.eq(chapterId), RESOURCE.ARCHIVED_AT.isNull).fetchOne()
				?: bad("Not one of this chapter's resources")
			if (r.managed == "unmanaged") bad("That resource is left unmanaged")
			if (dsl.fetchExists(PROJECT_RESOURCE, PROJECT_RESOURCE.PROJECT_ID.eq(project.id), PROJECT_RESOURCE.RESOURCE_ID.eq(r.id))) conflict("Already attached")
			r.id!!
		} else {
			val tool = input.tool ?: bad("Choose a tool")
			if (tool == "notion") bad("Notion pages come from the chapter's Notion routes")
			val (rule, hint) = NAME_RULES[tool] ?: bad("The portal can't manage $tool resources")
			val name = normalizeName(tool, input.name ?: "")
			if (!rule.matches(name)) bad("A $tool name needs $hint")
			if (existing(chapterId, tool, name) != null) conflict("This chapter already has $name in $tool: attach that one instead")
			insertResource(chapterId, tool, name, tags(input.tags))
		}
		dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project.id).set(PROJECT_RESOURCE.RESOURCE_ID, resourceId)
			.set(PROJECT_RESOURCE.AUDIENCE, input.audience).set(PROJECT_RESOURCE.AUDIENCE_ROLE_ID, input.roleId)
			.set(PROJECT_RESOURCE.ACCESS_LEVEL, input.access).set(PROJECT_RESOURCE.REQUIRES_AGREEMENT, input.requiresAgreement)
			.execute()
		if (input.resourceId != null && input.tags.isNotEmpty()) dsl.update(RESOURCE).set(RESOURCE.TAGS, tags(input.tags).toTypedArray()).where(RESOURCE.ID.eq(resourceId)).execute()
		touch(project)
		audit(viewer, chapterId, project.id!!, "project.resource_attach", "Attached ${resourceName(resourceId)}")
		return resourceId
	}

	@Transactional
	fun updateResource(chapterId: UUID, project: ProjectRecord, resourceId: UUID, input: ResourceInput, viewer: Viewer) {
		checkAudience(input)
		val updated = dsl.update(PROJECT_RESOURCE)
			.set(PROJECT_RESOURCE.AUDIENCE, input.audience).set(PROJECT_RESOURCE.AUDIENCE_ROLE_ID, input.roleId)
			.set(PROJECT_RESOURCE.ACCESS_LEVEL, input.access).set(PROJECT_RESOURCE.REQUIRES_AGREEMENT, input.requiresAgreement)
			.where(PROJECT_RESOURCE.PROJECT_ID.eq(project.id), PROJECT_RESOURCE.RESOURCE_ID.eq(resourceId)).execute()
		if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not attached to this project")
		dsl.update(RESOURCE).set(RESOURCE.TAGS, tags(input.tags).toTypedArray()).where(RESOURCE.ID.eq(resourceId)).execute()
		touch(project)
		audit(viewer, chapterId, project.id!!, "project.resource_update", "Changed who gets ${resourceName(resourceId)}")
	}

	@Transactional
	fun detachResource(chapterId: UUID, project: ProjectRecord, resourceId: UUID, viewer: Viewer) {
		val name = resourceName(resourceId)
		val deleted = dsl.deleteFrom(PROJECT_RESOURCE).where(PROJECT_RESOURCE.PROJECT_ID.eq(project.id), PROJECT_RESOURCE.RESOURCE_ID.eq(resourceId)).execute()
		if (deleted == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not attached to this project")
		// A resource that was never created in its tool and nothing else uses is dropped (archived), not left behind.
		val unused = !dsl.fetchExists(PROJECT_RESOURCE, PROJECT_RESOURCE.RESOURCE_ID.eq(resourceId)) &&
			!dsl.fetchExists(CHAPTER_RESOURCE, CHAPTER_RESOURCE.RESOURCE_ID.eq(resourceId))
		if (unused) dsl.update(RESOURCE).set(RESOURCE.ARCHIVED_AT, OffsetDateTime.now()).where(RESOURCE.ID.eq(resourceId), RESOURCE.EXTERNAL_ID.isNull).execute()
		touch(project)
		audit(viewer, chapterId, project.id!!, "project.resource_detach", "Detached $name")
	}

	private fun existing(chapterId: UUID, tool: String, name: String): UUID? =
		dsl.select(RESOURCE.ID).from(RESOURCE)
			.where(RESOURCE.CHAPTER_ID.eq(chapterId), RESOURCE.TOOL.eq(tool), DSL.lower(RESOURCE.NAME).eq(name.lowercase()), RESOURCE.ARCHIVED_AT.isNull)
			.fetchOne()?.value1()

	private fun insertResource(chapterId: UUID, tool: String, name: String, tags: List<String>): UUID =
		dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, tool).set(RESOURCE.NAME, name).set(RESOURCE.CHAPTER_ID, chapterId)
			.set(RESOURCE.TAGS, tags.toTypedArray()).set(RESOURCE.MANAGED, "portal")
			.returningResult(RESOURCE.ID).fetchSingle().value1()!!

	private fun partner(name: String?): UUID? {
		val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
		dsl.selectFrom(PARTNER).where(DSL.lower(PARTNER.NAME).eq(n.lowercase()), PARTNER.DELETED_AT.isNull).fetchOne()?.let { return it.id }
		return dsl.insertInto(PARTNER).set(PARTNER.NAME, n).returningResult(PARTNER.ID).fetchSingle().value1()
	}

	private fun checkTerm(chapterId: UUID, termId: UUID?) {
		if (termId == null) return
		val ok = dsl.fetchExists(
			DSL.selectOne().from(TERM).join(CHAPTER).on(CHAPTER.INSTITUTION_ID.eq(TERM.INSTITUTION_ID)).where(TERM.ID.eq(termId), CHAPTER.ID.eq(chapterId)),
		)
		if (!ok) bad("That semester isn't this chapter's school's")
	}

	private fun checkRole(roleId: UUID?) {
		if (roleId != null && !dsl.fetchExists(PROJECT_ROLE, PROJECT_ROLE.ID.eq(roleId))) bad("No such project role")
	}

	private fun checkAudience(input: ResourceInput) {
		if (input.audience !in setOf("team", "role", "leads")) bad("Who gets it: the team, a role, or the project leads")
		if ((input.audience == "role") != (input.roleId != null)) bad("Choose a role for a role audience, and only then")
		checkRole(input.roleId)
		if (input.access !in setOf("read", "write", "admin")) bad("Access is read, write or admin")
	}

	private fun touch(project: ProjectRecord) = dsl.update(PROJECT).set(PROJECT.UPDATED_AT, OffsetDateTime.now()).where(PROJECT.ID.eq(project.id)).execute()

	private fun personName(id: UUID) = dsl.select(PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME).from(PERSON).where(PERSON.ID.eq(id))
		.fetchOne()?.let { ProjectQueries.name(it.value3(), it.value1()!!, it.value2()!!) } ?: "someone"

	private fun resourceName(id: UUID) = dsl.select(RESOURCE.TOOL, RESOURCE.NAME).from(RESOURCE).where(RESOURCE.ID.eq(id))
		.fetchOne()?.let { "${it.value2()} (${it.value1()})" } ?: "a resource"

	private fun audit(viewer: Viewer, chapterId: UUID, projectId: UUID, action: String, summary: String) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "project").set(AUDIT_EVENT.TARGET_ID, projectId)
			.set(AUDIT_EVENT.CHAPTER_ID, chapterId).set(AUDIT_EVENT.AFTER, JSONB.valueOf(json.writeValueAsString(mapOf("summary" to summary))))
			.execute()
	}
}
