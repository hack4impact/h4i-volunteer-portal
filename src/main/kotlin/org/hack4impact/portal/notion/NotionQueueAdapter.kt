package org.hack4impact.portal.notion

import org.hack4impact.portal.adapters.AccountRef
import org.hack4impact.portal.adapters.GrantResult
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.adoption.AccountMatcher
import org.hack4impact.portal.national.AdminQueue
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.springframework.stereotype.Component

/**
 * Notion membership through the admin queue (PRD: no member API on the Plus plan). Resources are chapter
 * teamspaces. Granting writes an "Add … to the Notion teamspace …" task; removing writes a "Remove …" task. What a
 * teamspace "has" is what the queue recorded as done (sync records with actual = present), since Notion can't be read.
 * There's nothing external to switch on, so it's always available; the Notion tool's dry-run setting still applies.
 */
@Component
class NotionQueueAdapter(private val dsl: DSLContext, private val queue: AdminQueue) : ReadAdapter, WriteAdapter {
	override val tool = Tool.NOTION

	override fun accounts(): List<ToolAccount> = emptyList()

	override fun resources(): List<ToolResource> =
		dsl.select(RESOURCE.EXTERNAL_ID, RESOURCE.NAME).from(RESOURCE)
			.where(RESOURCE.TOOL.eq("notion"), RESOURCE.EXTERNAL_ID.isNotNull, RESOURCE.ARCHIVED_AT.isNull)
			.fetch { ToolResource(it.value1()!!, it.value2()!!) }

	/** People the queue recorded as added. The account ID names the person (`person:<uuid>`); the address helps the queue's wording. */
	override fun members(resourceId: String): List<ResourceMember> {
		val resource = resourceOf(resourceId)
		return dsl.select(PERSON.ID, PERSON.ORG_EMAIL, PERSON.SCHOOL_EMAIL, PERSON.PERSONAL_EMAIL).from(SYNC_RECORD)
			.join(PERSON).on(PERSON.ID.eq(SYNC_RECORD.PERSON_ID))
			.where(SYNC_RECORD.RESOURCE_ID.eq(resource), SYNC_RECORD.ACTUAL.eq("present"))
			.fetch { r ->
				val email = r.value2() ?: r.value3() ?: r.value4()
				ResourceMember(AccountMatcher.PERSON_PREFIX + r.value1(), Access.WRITE, email)
			}
	}

	override fun create(name: String): String =
		throw Rejected(tool, "the portal can't create Notion teamspaces: create it in Notion and set its ID in the chapter's Notion settings")

	override fun grant(resourceId: String, account: AccountRef, access: Access): GrantResult {
		val person = account.person ?: throw Rejected(tool, "a queue task needs the person")
		val resource = resourceOf(resourceId)
		queue.open(tool, "add_member", person, resource, "Add ${who(account)} to the Notion teamspace ${teamspaceName(resource)}")
		return GrantResult.QUEUED
	}

	override fun revoke(resourceId: String, accountId: String): GrantResult {
		val resource = resourceOf(resourceId)
		val id = runCatching { java.util.UUID.fromString(accountId.removePrefix(AccountMatcher.PERSON_PREFIX)) }.getOrNull() ?: throw Rejected(tool, "not a portal person: $accountId")
		val person = dsl.select(PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.ORG_EMAIL, PERSON.SCHOOL_EMAIL).from(PERSON).where(PERSON.ID.eq(id)).fetchOne()
			?: throw Rejected(tool, "no person $id")
		val email = (person.value3() ?: person.value4())?.let { " ($it)" }.orEmpty()
		queue.open(tool, "remove_member", id, resource, "Remove ${person.value1()} ${person.value2()}$email from the Notion teamspace ${teamspaceName(resource)}")
		return GrantResult.QUEUED
	}

	override fun archive(resourceId: String) = Unit // teamspaces outlive projects

	private fun who(account: AccountRef) = listOfNotNull(account.name, account.email?.let { "($it)" }).joinToString(" ").ifEmpty { "a member" }

	private fun resourceOf(externalId: String) =
		dsl.select(RESOURCE.ID).from(RESOURCE).where(RESOURCE.TOOL.eq("notion"), RESOURCE.EXTERNAL_ID.eq(externalId)).fetchOne()?.value1()
			?: throw org.hack4impact.portal.adapters.NotFound(tool, "teamspace $externalId")

	private fun teamspaceName(resource: java.util.UUID): String =
		dsl.select(RESOURCE.NAME, CHAPTER.NAME).from(RESOURCE).leftJoin(CHAPTER).on(CHAPTER.ID.eq(RESOURCE.CHAPTER_ID)).where(RESOURCE.ID.eq(resource)).fetchOne()
			?.let { it.value1() ?: it.value2() } ?: "?"
}
