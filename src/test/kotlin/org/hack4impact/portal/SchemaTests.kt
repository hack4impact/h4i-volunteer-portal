package org.hack4impact.portal

import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class SchemaTests(@Autowired private val dsl: DSLContext) {

	@Test
	fun `every tool starts enabled and in dry run`() {
		val settings = dsl.selectFrom(TOOL_SETTING).fetch()
		assertEquals(setOf("google", "github", "slack", "notion", "vaultwarden", "documenso"), settings.map { it.tool }.toSet())
		assertTrue(settings.all { it.dryRun == true && it.enabled == true })
	}

	@Test
	fun `person status must be one of the PRD statuses`() {
		assertFailsWith<DataIntegrityViolationException> {
			dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "A").set(PERSON.LAST_NAME, "B").set(PERSON.STATUS, "hiatus").execute()
		}
	}

	@Test
	fun `a tool account needs an ID or a login`() {
		val person = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "A").set(PERSON.LAST_NAME, "B")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		assertFailsWith<DataIntegrityViolationException> {
			dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, person).set(TOOL_ACCOUNT.TOOL, "github").execute()
		}
	}

	@Test
	fun `tool names are limited to the managed tools`() {
		val person = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "A").set(PERSON.LAST_NAME, "B")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		assertFailsWith<DataIntegrityViolationException> {
			dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, person).set(TOOL_ACCOUNT.TOOL, "linkedin")
				.set(TOOL_ACCOUNT.EXTERNAL_LOGIN, "x").execute()
		}
	}
}
