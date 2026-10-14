package org.hack4impact.portal.sync

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.registration.csvCells
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Checks for fixes from the 2026-10-13 code review that don't belong to one feature's tests. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ReviewFixTests(
	@Autowired private val dsl: DSLContext,
	@Autowired private val locks: RunLocks,
	@Autowired private val syncs: SyncRequests,
	@Autowired private val mvc: MockMvc,
	@Autowired private val recovery: SyncRecovery,
) {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portal.sync.on-request") { "false" }
		}
	}

	@BeforeEach
	fun empty() = dsl.emptyPortalTables()

	@Test
	fun `a run cut short by a crash is marked failed at startup and every tool is synced once (second review L5)`() {
		val finished = dsl.insertInto(SYNC_RUN).set(SYNC_RUN.TOOL, "slack").set(SYNC_RUN.MODE, "apply").set(SYNC_RUN.TRIGGER, "manual").set(SYNC_RUN.STATUS, "completed")
			.returning(SYNC_RUN.ID).fetchSingle().id
		val cut = dsl.insertInto(SYNC_RUN).set(SYNC_RUN.TOOL, "slack").set(SYNC_RUN.MODE, "apply").set(SYNC_RUN.TRIGGER, "manual").set(SYNC_RUN.STATUS, "running")
			.returning(SYNC_RUN.ID).fetchSingle().id
		val requested = { dsl.fetchCount(dsl.selectFrom("portal.event_publication").where("serialized_event LIKE '%recovery%'")) }
		val before = requested()
		assertEquals(1, recovery.recover())
		assertEquals("failed", dsl.select(SYNC_RUN.STATUS).from(SYNC_RUN).where(SYNC_RUN.ID.eq(cut)).fetchSingle().value1())
		assertEquals("completed", dsl.select(SYNC_RUN.STATUS).from(SYNC_RUN).where(SYNC_RUN.ID.eq(finished)).fetchSingle().value1())
		assertEquals(before + 1, requested())
		assertEquals(0, recovery.recover()) // nothing cut short: no extra sync
		assertEquals(before + 1, requested())
	}

	@Test
	fun `every trigger the code can ask for is one a sync run can record (review T1, H1)`() {
		for (trigger in SyncTriggers.ALL) {
			dsl.insertInto(SYNC_RUN).set(SYNC_RUN.TOOL, "slack").set(SYNC_RUN.MODE, "dry_run").set(SYNC_RUN.TRIGGER, trigger).set(SYNC_RUN.STATUS, "completed").execute()
		}
		assertEquals(SyncTriggers.ALL.size, dsl.fetchCount(SYNC_RUN))
		// One the database would refuse fails where it's asked for, not later in the background.
		assertFailsWith<IllegalArgumentException> { syncs.request("typo") }
	}

	@Test
	fun `work under one lock never overlaps (review T2, H2)`() {
		val running = AtomicInteger()
		val most = AtomicInteger()
		val pool = Executors.newFixedThreadPool(4)
		repeat(8) {
			pool.submit {
				locks.exclusive(RunLocks.SYNC) {
					most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
					Thread.sleep(30)
					running.decrementAndGet()
				}
			}
		}
		pool.shutdown()
		pool.awaitTermination(30, TimeUnit.SECONDS)
		assertEquals(1, most.get())
	}

	@Test
	fun `a removed person keeps no chapter role (review M4)`() {
		val umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		val rex = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Rex").set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "removed").set(PERSON.ORG_EMAIL, "rex@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, rex).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
		dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, rex).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "lead").execute() // never ended
		val signedIn = oidcLogin().idToken { it.subject("sub-rex").claim("email", "rex@hack4impact.org").claim("email_verified", true).claim("hd", "hack4impact.org") }
		mvc.get("/api/me") { with(signedIn) }.andExpect { jsonPath("$.chapters.length()") { value(0) } }
		mvc.get("/api/chapters/umd") { with(signedIn) }.andExpect { status { isForbidden() } }
	}

	@Test
	fun `a reused address doesn't sign in as the person whose Google account it was (second review H4)`() {
		val old = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Ada").set(PERSON.LAST_NAME, "Old").set(PERSON.STATUS, "alumni").set(PERSON.ORG_EMAIL, "ada@umd.hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!
		dsl.insertInto(org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT).set(org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT.PERSON_ID, old)
			.set(org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT.TOOL, "google").set(org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT.EXTERNAL_ID, "sub-old")
			.set(org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT.EXTERNAL_LOGIN, "ada@umd.hack4impact.org").execute()
		fun signIn(subject: String) = oidcLogin().idToken { it.subject(subject).claim("email", "ada@umd.hack4impact.org").claim("email_verified", true).claim("hd", "umd.hack4impact.org") }
		mvc.get("/api/me") { with(signIn("sub-old")) }.andExpect { jsonPath("$.personId") { value(old.toString()) } }
		// A new Google account at the same address (the old one deleted, the address given to someone new): not Ada.
		mvc.get("/api/me") { with(signIn("sub-new")) }.andExpect { jsonPath("$.personId") { doesNotExist() } }
	}

	@Test
	fun `a roster line keeps commas inside quotes (review L3)`() {
		assertEquals(listOf("Ada", "Lovelace, Countess", "ada@umd.edu", ""), csvCells("""Ada,"Lovelace, Countess", ada@umd.edu,"""))
		assertEquals(listOf("Say \"hi\"", "x"), csvCells("\"Say \"\"hi\"\"\",x"))
	}
}
