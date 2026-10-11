package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import java.util.UUID

/**
 * Loads the sandbox fixture's people into [chapterId]: each with their status and chapter role, and each tool
 * account matched by login, email or ID in that tool (sandbox check, step 6; sandbox suite, step 9).
 * Returns name → person ID. With [skipDisabledTools], accounts in tools whose adapter isn't enabled are left out
 * (the nightly suite runs without Google); otherwise they're an error.
 */
@Suppress("UNCHECKED_CAST")
internal fun loadFixturePeople(dsl: DSLContext, fixture: Map<String, Any?>, tools: Map<Tool, ReadAdapter>, chapterId: UUID, skipDisabledTools: Boolean = false): Map<String, UUID> {
	val accountsByTool = mutableMapOf<Tool, List<ToolAccount>>()
	return (fixture["people"] as List<Map<String, Any?>>).associate { p ->
		val name = p["name"] as String
		val id = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, name.substringBefore(' ')).set(PERSON.LAST_NAME, name.substringAfter(' ', "Sandbox"))
			.set(PERSON.STATUS, (p["status"] as String?) ?: "active").returningResult(PERSON.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapterId).execute()
		(p["role"] as String?)?.let { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, id).set(CHAPTER_ROLE.CHAPTER_ID, chapterId).set(CHAPTER_ROLE.ROLE, it).execute() }
		((p["accounts"] as Map<String, String>?) ?: emptyMap()).forEach { (toolName, login) ->
			val tool = Tool.valueOf(toolName.uppercase())
			if (skipDisabledTools && tool !in tools) return@forEach
			val adapter = tools[tool] ?: error("$name: the $tool adapter isn't enabled")
			val accounts = accountsByTool.getOrPut(tool) { adapter.accounts() }
			val account = accounts.firstOrNull { listOf(it.login, it.email, it.externalId).any { v -> v.equals(login, ignoreCase = true) } && it.externalId != null }
				?: error("$name: no $tool account with login, email or ID '$login'")
			dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, id).set(TOOL_ACCOUNT.TOOL, tool.name.lowercase())
				.set(TOOL_ACCOUNT.EXTERNAL_ID, account.externalId).set(TOOL_ACCOUNT.EXTERNAL_LOGIN, account.login).set(TOOL_ACCOUNT.STATE, "confirmed").execute()
		}
		name to id
	}
}
