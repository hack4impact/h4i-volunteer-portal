package org.hack4impact.portal.national

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.sync.FakeTools
import org.hack4impact.portal.sync.SyncEngine
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Build plan step 9: national controls, the admin queue (Notion membership, held removals) and dead letters. */
@Import(TestcontainersConfiguration::class, FakeTools::class)
@SpringBootTest
@AutoConfigureMockMvc
class NationalTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val engine: SyncEngine,
	@Autowired private val slack: InMemoryReadAdapter,
) {
	companion object {
		@JvmStatic
		@org.springframework.test.context.DynamicPropertySource
		fun properties(registry: org.springframework.test.context.DynamicPropertyRegistry) {
			// The tests run the engine themselves; a background run would race them (e.g. after confirming removals).
			registry.add("portal.sync.on-request") { "false" }
		}
	}

	private lateinit var umd: UUID
	private lateinit var ada: UUID
	private val teamspace = "11111111-2222-3333-4444-555555555555"

	private fun signedIn(email: String) = oidcLogin().idToken {
		it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	private fun call(method: String, path: String, body: String? = null, as_: String = "nora@hack4impact.org"): ResultActionsDsl {
		val setup: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
			with(signedIn(as_)); with(csrf())
			if (body != null) { contentType = MediaType.APPLICATION_JSON; content = body }
		}
		return when (method) {
			"POST" -> mvc.post(path) { setup() }
			"PUT" -> mvc.put(path) { setup() }
			else -> mvc.get(path) { setup() }
		}
	}

	private fun person(first: String, chapter: UUID, email: String): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, email)
			.returningResult(PERSON.ID).fetchSingle().value1()!!
			.also { dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, it).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapter).execute() }

	private fun notionRun() = engine.run("manual", setOf(Tool.NOTION)).single()

	private fun openTasks(action: String) = dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.ACTION.eq(action), ADMIN_TASK.STATUS.eq("open")).fetch()

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "University of Maryland").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", umd, "lena@hack4impact.org").also { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, it).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "lead").execute() }
		ada = person("Ada", umd, "ada@hack4impact.org")
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Nora").set(PERSON.LAST_NAME, "National").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "nora@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		slack.failure = null; slack.failWrites = null; slack.writes.clear(); slack.resources = emptyMap(); slack.accounts = emptyList()
		// The lead saves the chapter's teamspace in the Notion settings (step 8): it becomes a chapter resource.
		call("PUT", "/api/chapters/umd/notion", """{"teamspaceId":"$teamspace","titlePattern":"{project}"}""", as_ = "lena@hack4impact.org").andExpect { status { isOk() } }
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.eq("notion")).execute()
	}

	@Test
	fun `Notion membership becomes queue tasks, and marking one done counts as applied`() {
		notionRun()
		val adds = openTasks("add_member")
		assertEquals(setOf("Add Ada Test (ada@hack4impact.org) to the Notion teamspace University of Maryland", "Add Lena Test (lena@hack4impact.org) to the Notion teamspace University of Maryland"),
			adds.map { it.description }.toSet())
		assertEquals("manual_task", dsl.select(SYNC_RECORD.STATUS).from(SYNC_RECORD).where(SYNC_RECORD.PERSON_ID.eq(ada)).fetchSingle().value1())

		notionRun()
		assertEquals(2, openTasks("add_member").size) // no duplicates

		val adaTask = adds.single { it.personId == ada }.id!!
		call("POST", "/api/national/queue/$adaTask", """{"action":"done"}""").andExpect { status { isOk() } }
		with(dsl.selectFrom(SYNC_RECORD).where(SYNC_RECORD.PERSON_ID.eq(ada)).fetchSingle()) {
			assertEquals("in_sync", status)
			assertNotNull(appliedAt)
		}
		val next = notionRun()
		assertEquals(1, next.adds) // only Lena is still to do

		// Ada leaves the chapter: a removal task, and marking it done records her as gone.
		dsl.update(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.LEFT_ON, java.time.LocalDate.now()).where(CHAPTER_MEMBERSHIP.PERSON_ID.eq(ada)).execute()
		notionRun()
		val removal = openTasks("remove_member").single()
		assertTrue(removal.description!!.startsWith("Remove Ada Test (ada@hack4impact.org) from the Notion teamspace"))
		// Until someone does it, she's still in the teamspace as far as the portal knows.
		with(dsl.selectFrom(SYNC_RECORD).where(SYNC_RECORD.PERSON_ID.eq(ada)).fetchSingle()) {
			assertEquals("manual_task", status)
			assertNotNull(appliedAt)
		}
		notionRun()
		assertEquals(1, openTasks("remove_member").size) // no duplicate while it waits
		call("POST", "/api/national/queue/${removal.id}", """{"action":"done"}""").andExpect { status { isOk() } }
		assertNull(dsl.select(SYNC_RECORD.APPLIED_AT).from(SYNC_RECORD).where(SYNC_RECORD.PERSON_ID.eq(ada)).fetchSingle().value1())
	}

	@Test
	fun `a task that's no longer needed cancels itself`() {
		notionRun()
		dsl.update(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.LEFT_ON, java.time.LocalDate.now()).where(CHAPTER_MEMBERSHIP.PERSON_ID.eq(ada)).execute()
		notionRun()
		val task = dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.PERSON_ID.eq(ada)).fetchSingle()
		assertEquals("cancelled", task.status)
		assertEquals("no longer needed", task.cancelledReason)
	}

	@Test
	fun `tasks about a person but no resource are left alone by the sync`() {
		// Like step 10's create_account: a person, no resource. Reconciling used to fail on these.
		val other = dsl.insertInto(ADMIN_TASK).set(ADMIN_TASK.TOOL, "notion").set(ADMIN_TASK.ACTION, "create_account").set(ADMIN_TASK.PERSON_ID, ada)
			.set(ADMIN_TASK.DESCRIPTION, "Create an account for Ada").returningResult(ADMIN_TASK.ID).fetchSingle().value1()!!
		notionRun()
		assertEquals("open", dsl.select(ADMIN_TASK.STATUS).from(ADMIN_TASK).where(ADMIN_TASK.ID.eq(other)).fetchSingle().value1())
	}

	@Test
	fun `held removals go ahead once national confirms them`() {
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).set(TOOL_SETTING.MAX_REMOVALS, 0).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, ada).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, "U-ada").execute()
		slack.accounts = listOf(ToolAccount("U-ada", "ada", "ada@hack4impact.org", null, AccountState.ACTIVE))
		val project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "RISE").set(PROJECT.SLUG, "rise").set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		val channel = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "slack").set(RESOURCE.NAME, "umd-rise").set(RESOURCE.CHAPTER_ID, umd).returningResult(RESOURCE.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project).set(PROJECT_RESOURCE.RESOURCE_ID, channel).set(PROJECT_RESOURCE.AUDIENCE, "team").set(PROJECT_RESOURCE.ACCESS_LEVEL, "write").execute()
		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project).set(PROJECT_MEMBER.PERSON_ID, ada).execute()
		engine.run("manual", setOf(Tool.SLACK))
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).execute()

		val seen = engine.run("manual", setOf(Tool.SLACK)).single()
		assertEquals("paused", seen.status)
		val held = engine.run("manual", setOf(Tool.SLACK)).single() // a later run rewrites the task
		val confirm = openTasks("confirm_removals").single()
		// Only the run the page showed can be confirmed (second review L4).
		call("POST", "/api/national/queue/${confirm.id}", """{"action":"done","runId":"${seen.runId}"}""").andExpect { status { isConflict() } }
		call("POST", "/api/national/queue/${confirm.id}", """{"action":"done"}""").andExpect { status { isConflict() } }
		val requested = { dsl.fetchCount(dsl.selectFrom("portal.event_publication").where("serialized_event LIKE '%SLACK%'")) }
		val before = requested()
		call("POST", "/api/national/queue/${confirm.id}", """{"action":"done","runId":"${held.runId}"}""").andExpect { status { isOk() } }
		assertEquals(before + 1, requested()) // confirming asks for a Slack run (review M9); the test makes it below
		slack.writes.clear()
		val confirmed = engine.run("manual", setOf(Tool.SLACK)).single()
		assertEquals("completed", confirmed.status)
		assertEquals(listOf("revoke new-umd-rise U-ada"), slack.writes)
	}

	@Test
	fun `only national changes tools, a tool can't leave dry run without its write switch, and pausing needs a reason`() {
		call("GET", "/api/national/tools", as_ = "lena@hack4impact.org").andExpect { status { isForbidden() } }
		call("GET", "/api/national/tools").andExpect {
			status { isOk() }
			jsonPath("$[?(@.tool == 'slack')].writable") { value(true) } // the fake has writes
			jsonPath("$[?(@.tool == 'documenso')].writable") { value(false) }
		}
		call("PUT", "/api/national/tools/documenso", """{"enabled":true,"dryRun":false}""").andExpect { status { isConflict() } }
		call("PUT", "/api/national/tools/slack", """{"enabled":false,"dryRun":true}""").andExpect { status { isBadRequest() } }
		call("PUT", "/api/national/tools/slack", """{"enabled":false,"dryRun":true,"reason":"Slack outage"}""").andExpect {
			status { isOk() }
			jsonPath("$[?(@.tool == 'slack')].pauseReason") { value("Slack outage") }
		}
		assertEquals(false, dsl.select(TOOL_SETTING.ENABLED).from(TOOL_SETTING).where(TOOL_SETTING.TOOL.eq("slack")).fetchSingle().value1())
	}

	@Test
	fun `dead letters are listed and put back in line`() {
		notionRun()
		dsl.update(SYNC_RECORD).set(SYNC_RECORD.STATUS, "dead_letter").set(SYNC_RECORD.ATTEMPTS, 5).set(SYNC_RECORD.LAST_ERROR, "boom").where(SYNC_RECORD.PERSON_ID.eq(ada)).execute()
		call("GET", "/api/national/dead-letters").andExpect {
			jsonPath("$.length()") { value(1) }
			jsonPath("$[0].person") { value("Ada Test") }
			jsonPath("$[0].lastError") { value("boom") }
		}
		call("POST", "/api/national/dead-letters/retry", "[]").andExpect { jsonPath("$.length()") { value(0) } }
		assertEquals("pending", dsl.select(SYNC_RECORD.STATUS).from(SYNC_RECORD).where(SYNC_RECORD.PERSON_ID.eq(ada)).fetchSingle().value1())
	}
}
