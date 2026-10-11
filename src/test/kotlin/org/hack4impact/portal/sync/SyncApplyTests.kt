package org.hack4impact.portal.sync

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.AuthFailed
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.Unavailable
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Build plan step 9: the sync engine changing tools, against in-memory tools. */
@Import(TestcontainersConfiguration::class, FakeTools::class)
@SpringBootTest
class SyncApplyTests(
	@Autowired private val dsl: DSLContext,
	@Autowired private val engine: SyncEngine,
	@Autowired private val slack: InMemoryReadAdapter,
	@Autowired private val github: InMemoryReadAdapter,
) {
	private lateinit var umd: UUID
	private lateinit var project: UUID
	private lateinit var channel: UUID
	private val people = mutableMapOf<String, UUID>()

	private fun person(name: String, slackId: String?, status: String = "active"): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, name).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, status)
			.set(PERSON.ORG_EMAIL, "${name.lowercase()}@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { id ->
				people[name] = id
				dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
				slackId?.let { dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, id).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, it).execute() }
			}

	private fun member(name: String) =
		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project).set(PROJECT_MEMBER.PERSON_ID, people.getValue(name)).execute()

	private fun applying(tool: String) = dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.eq(tool)).execute()

	private fun slackRun() = engine.run("manual", setOf(Tool.SLACK)).single()

	private fun outcomes(runId: UUID?) = dsl.select(SYNC_CHANGE.KIND, SYNC_CHANGE.OUTCOME).from(SYNC_CHANGE).where(SYNC_CHANGE.RUN_ID.eq(runId))
		.fetch { "${it.value1()}:${it.value2()}" }.sorted()

	private fun record(name: String) = dsl.selectFrom(SYNC_RECORD).where(SYNC_RECORD.PERSON_ID.eq(people.getValue(name)), SYNC_RECORD.RESOURCE_ID.eq(channel)).fetchOne()

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		people.clear()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Ada", "U-ada")
		person("Bo", "U-bo")
		person("Cy", null) // no Slack account yet
		project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "RISE DC").set(PROJECT.SLUG, "rise-dc").set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		// Made in the portal (step 8), not created in Slack yet.
		channel = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "slack").set(RESOURCE.NAME, "umd-rise-dc").set(RESOURCE.CHAPTER_ID, umd)
			.returningResult(RESOURCE.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project).set(PROJECT_RESOURCE.RESOURCE_ID, channel)
			.set(PROJECT_RESOURCE.AUDIENCE, "team").set(PROJECT_RESOURCE.ACCESS_LEVEL, "write").execute()
		member("Ada")
		member("Bo")
		for (fake in listOf(slack, github)) { fake.failure = null; fake.failWrites = null; fake.writes.clear(); fake.resources = emptyMap(); fake.accounts = emptyList() }
		slack.accounts = listOf(
			ToolAccount("U-ada", "ada", "ada@hack4impact.org", null, AccountState.ACTIVE),
			ToolAccount("U-bo", "bo", "bo@hack4impact.org", null, AccountState.ACTIVE),
		)
	}

	@Test
	fun `a tool in dry run is never changed`() {
		val run = slackRun()
		assertEquals(emptyList(), slack.writes)
		assertEquals(0, run.applied)
		assertTrue(outcomes(run.runId).none { it.endsWith(":applied") })
	}

	@Test
	fun `out of dry run, the channel is created and the team added, and the next run has nothing to do`() {
		applying("slack")
		val first = slackRun()
		assertEquals("create umd-rise-dc", slack.writes.first()) // created before anyone is added
		assertEquals(setOf("grant new-umd-rise-dc U-ada WRITE", "grant new-umd-rise-dc U-bo WRITE"), slack.writes.drop(1).toSet())
		assertEquals("completed", first.status)
		assertEquals(2, first.applied)
		assertEquals(listOf("add:applied", "add:applied", "create_resource:applied"), outcomes(first.runId))
		assertEquals("new-umd-rise-dc", dsl.select(RESOURCE.EXTERNAL_ID).from(RESOURCE).where(RESOURCE.ID.eq(channel)).fetchSingle().value1())
		with(record("Ada")!!) {
			assertEquals("in_sync", status)
			assertNotNull(appliedAt)
		}

		slack.writes.clear()
		val second = slackRun()
		assertEquals(emptyList(), slack.writes) // idempotent: nothing left to change
		assertEquals(0, second.adds)
	}

	@Test
	fun `someone without an account the tool knows is invited or waited for, and still counts as granted`() {
		applying("slack")
		member("Cy")
		slackRun()
		assertTrue("invite new-umd-rise-dc cy@hack4impact.org" in slack.writes)
		with(record("Cy")!!) {
			assertEquals("waiting_on_member", status)
			assertNotNull(appliedAt) // the portal gave it, so the portal may take it back
		}
	}

	@Test
	fun `access the portal applied and no longer wants is removed, and access it never gave is left alone`() {
		applying("slack")
		slackRun()
		// Bo leaves the project; someone the portal never added (Ada's alt account) appears by hand.
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).where(PROJECT_MEMBER.PERSON_ID.eq(people.getValue("Bo"))).execute()
		person("Di", "U-di")
		slack.accounts = slack.accounts + ToolAccount("U-di", "di", null, null, AccountState.ACTIVE)
		val key = slack.resources.keys.single()
		slack.resources = mapOf(key to slack.resources.getValue(key) + ResourceMember("U-di", Access.WRITE))
		slack.writes.clear()

		val run = slackRun()
		assertEquals(listOf("revoke new-umd-rise-dc U-bo"), slack.writes)
		assertEquals(1, run.removed)
		assertEquals(1, run.drift) // Di stays: the portal never gave it
		with(record("Bo")!!) {
			assertEquals("absent", desired)
			assertEquals("in_sync", status)
			assertNull(appliedAt)
		}
	}

	@Test
	fun `over the blast-radius limit removals are held for national while adds still happen`() {
		applying("slack")
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.MAX_REMOVALS, 0).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		slackRun()
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).where(PROJECT_MEMBER.PERSON_ID.eq(people.getValue("Bo"))).execute()
		member("Cy")
		slack.writes.clear()

		val run = slackRun()
		assertEquals("paused", run.status)
		assertEquals(listOf("invite new-umd-rise-dc cy@hack4impact.org"), slack.writes) // no revoke
		assertTrue("remove:held" in outcomes(run.runId))
		val task = dsl.selectFrom(ADMIN_TASK).where(ADMIN_TASK.RUN_ID.eq(run.runId)).fetchSingle()
		assertEquals("confirm_removals", task.action)
		assertEquals("open", task.status)
	}

	@Test
	fun `a failed change is retried with backoff, then becomes a dead letter`() {
		applying("slack")
		slack.resources = mapOf(ToolResource("C1", "umd-rise-dc") to emptyList())
		dsl.update(RESOURCE).set(RESOURCE.EXTERNAL_ID, "C1").where(RESOURCE.ID.eq(channel)).execute()
		slack.failWrites = Unavailable(Tool.SLACK, "down")

		slackRun()
		with(record("Ada")!!) {
			assertEquals("failed", status)
			assertEquals(1, attempts)
			assertTrue(nextRetryAt!!.isAfter(OffsetDateTime.now()))
		}
		val tooSoon = slackRun()
		assertTrue(outcomes(tooSoon.runId).all { it == "add:skipped" }) // not due yet
		assertEquals(1, record("Ada")!!.attempts)

		dsl.update(SYNC_RECORD).set(SYNC_RECORD.ATTEMPTS, SyncApplier.MAX_ATTEMPTS - 1).set(SYNC_RECORD.NEXT_RETRY_AT, OffsetDateTime.now().minusMinutes(1)).execute()
		slackRun()
		assertEquals("dead_letter", record("Ada")!!.status)

		slack.failWrites = null
		val waiting = slackRun()
		assertTrue(outcomes(waiting.runId).all { it == "add:skipped" }) // no more automatic retries
		assertEquals("dead_letter", record("Ada")!!.status)

		dsl.update(SYNC_RECORD).set(SYNC_RECORD.STATUS, "pending").set(SYNC_RECORD.ATTEMPTS, 0).execute() // a person resets it
		slackRun()
		assertEquals("in_sync", record("Ada")!!.status)
	}

	@Test
	fun `bad credentials stop the tool's run at once`() {
		applying("slack")
		slack.failWrites = AuthFailed(Tool.SLACK, "invalid_auth")
		val run = slackRun()
		assertEquals("failed", run.status)
		assertEquals(listOf("add:skipped", "add:skipped", "create_resource:failed"), outcomes(run.runId))
	}

	@Test
	fun `closing a project empties its channel and then archives it, but not while removals are held`() {
		applying("slack")
		slackRun()
		dsl.update(PROJECT).set(PROJECT.STATUS, "closed").where(PROJECT.ID.eq(project)).execute()
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.MAX_REMOVALS, 0).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		slack.writes.clear()
		val held = slackRun()
		assertEquals(emptyList(), slack.writes)
		assertTrue("archive_resource:held" in outcomes(held.runId))

		dsl.update(TOOL_SETTING).set(TOOL_SETTING.MAX_REMOVALS, 25).where(TOOL_SETTING.TOOL.eq("slack")).execute()
		val run = slackRun()
		assertEquals("archive new-umd-rise-dc", slack.writes.last()) // after both members are out
		assertEquals(setOf("revoke new-umd-rise-dc U-ada", "revoke new-umd-rise-dc U-bo"), slack.writes.dropLast(1).toSet())
		assertEquals(1, run.archives)
		assertNotNull(dsl.select(RESOURCE.ARCHIVED_AT).from(RESOURCE).where(RESOURCE.ID.eq(channel)).fetchSingle().value1())
	}

	@Test
	fun `access someone has through their role in the tool is left alone`() {
		applying("slack")
		slack.resources = mapOf(ToolResource("C1", "umd-rise-dc") to listOf(ResourceMember("U-ada", Access.ADMIN, implicit = true)))
		dsl.update(RESOURCE).set(RESOURCE.EXTERNAL_ID, "C1").where(RESOURCE.ID.eq(channel)).execute()
		val run = slackRun()
		assertEquals(listOf("grant C1 U-bo WRITE"), slack.writes) // Ada isn't added: her role already gives it
		assertEquals(0, run.drift)

		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).where(PROJECT_MEMBER.PERSON_ID.eq(people.getValue("Ada"))).execute()
		slack.writes.clear()
		val after = slackRun()
		assertEquals(emptyList(), slack.writes) // and never removed or reported as drift
		assertEquals(0, after.drift)
	}
}
