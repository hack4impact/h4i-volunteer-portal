package org.hack4impact.portal.registration

import org.hack4impact.portal.db.tables.records.RegistrationRecord
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.util.UUID

data class MatchResult(val status: String, val people: Map<UUID, List<String>>)

/**
 * Matches a submitted registration against everyone the portal knows, imported members included (PRD: new,
 * returning, alumni record found, or possible duplicate). Strong evidence is an email address or the GitHub
 * account; a same-name person in the chapter is only weak evidence. Never merges anyone: the lead decides.
 */
@Component
class RegistrationMatcher(private val dsl: DSLContext) {
	fun match(r: RegistrationRecord): MatchResult {
		val why = mutableMapOf<UUID, MutableList<String>>()
		val emails = listOfNotNull(r.schoolEmail, r.personalEmail).map { it.trim().lowercase() }.distinct()
		if (emails.isNotEmpty()) {
			dsl.select(PERSON.ID).from(PERSON)
				.where(PERSON.DELETED_AT.isNull, DSL.or(listOf(PERSON.SCHOOL_EMAIL, PERSON.PERSONAL_EMAIL, PERSON.ORG_EMAIL).map { DSL.lower(it).`in`(emails) }))
				.fetch().forEach { why.getOrPut(it.value1()!!) { mutableListOf() } += "same email" }
		}
		if (r.githubId != null || r.githubLogin != null) {
			dsl.select(TOOL_ACCOUNT.PERSON_ID).from(TOOL_ACCOUNT)
				.where(TOOL_ACCOUNT.TOOL.eq("github"), DSL.or(TOOL_ACCOUNT.EXTERNAL_ID.eq(r.githubId), DSL.lower(TOOL_ACCOUNT.EXTERNAL_LOGIN).eq(r.githubLogin?.lowercase())))
				.fetch().forEach { why.getOrPut(it.value1()!!) { mutableListOf() } += "same GitHub account" }
		}
		val strong = why.keys.toSet()
		if (r.firstName != null && r.lastName != null) {
			dsl.select(PERSON.ID).from(PERSON).join(CHAPTER_MEMBERSHIP).on(CHAPTER_MEMBERSHIP.PERSON_ID.eq(PERSON.ID))
				.where(PERSON.DELETED_AT.isNull, CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(r.chapterId),
					DSL.lower(PERSON.FIRST_NAME).eq(r.firstName!!.trim().lowercase()), DSL.lower(PERSON.LAST_NAME).eq(r.lastName!!.trim().lowercase()))
				.fetch().forEach { why.getOrPut(it.value1()!!) { mutableListOf() } += "same name in this chapter" }
		}
		r.personId?.let { why.remove(it) } // a claim is its own record
		val status = when {
			why.isEmpty() -> "new"
			strong.size == 1 && why.size == 1 -> {
				val s = dsl.select(PERSON.STATUS).from(PERSON).where(PERSON.ID.eq(strong.single())).fetchSingle().value1()
				if (s == "alumni") "alumni" else "returning"
			}
			else -> "duplicate"
		}
		return MatchResult(status, why.mapValues { it.value.distinct() })
	}
}
