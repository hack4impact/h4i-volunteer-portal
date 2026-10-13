package org.hack4impact.portal.drafts

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHANGE
import org.hack4impact.portal.db.tables.references.CHANGESET
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.mail.MailSender
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftMail : MailSender {
	data class Sent(val to: String, val subject: String, val text: String)

	val sent = java.util.concurrent.CopyOnWriteArrayList<Sent>()
	override fun send(to: String, subject: String, text: String) { sent += Sent(to, subject, text) }
}

@TestConfiguration(proxyBeanMethods = false)
class DraftFakes {
	@Bean
	@Primary
	fun draftMail() = DraftMail()
}

/** Build plan step 12: a semester's plan in a draft, previewed, then released in one transaction. */
@Import(TestcontainersConfiguration::class, DraftFakes::class)
@SpringBootTest
@AutoConfigureMockMvc
class DraftTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val drafts: Drafts,
	@Autowired private val mail: DraftMail,
) {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portal.sync.on-request") { "false" }
			registry.add("portal.agreements.on-request") { "false" }
			registry.add("portal.drafts.schedule-enabled") { "false" }
		}
	}

	private lateinit var umd: UUID
	private lateinit var ada: UUID
	private lateinit var bo: UUID
	private val lead = "lena@hack4impact.org"

	private fun signedIn(email: String) = oidcLogin().idToken { it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org") }

	private fun body(result: ResultActionsDsl): JsonNode = HttpJson.MAPPER.readTree(result.andReturn().response.contentAsString)

	private fun post(path: String, json: String, as_: String = lead): ResultActionsDsl = mvc.post(path) {
		with(csrf()); with(signedIn(as_)); contentType = MediaType.APPLICATION_JSON; content = json
	}

	private fun get(path: String, as_: String = lead) = mvc.get(path) { with(signedIn(as_)) }

	private fun person(first: String, role: String? = null): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "${first.lowercase()}@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { id ->
				dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute()
				role?.let { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, id).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, it).execute() }
			}

	private fun newDraft(name: String = "Fall 2026 teams"): String = body(post("/api/chapters/umd/drafts", """{"name":"$name"}""").andExpect { status { isCreated() } }).path("id").asString()

	/** The semester plan: a new project, two people on it, started. */
	private fun plan(draft: String) {
		post("/api/chapters/umd/projects?draft=$draft", """{"name":"RISE DC"}""").andExpect { status { isCreated() } }
		post("/api/chapters/umd/projects/rise-dc/members?draft=$draft", """{"personId":"$ada"}""").andExpect { status { isOk() } }
		post("/api/chapters/umd/projects/rise-dc/members?draft=$draft", """{"personId":"$bo"}""").andExpect { status { isOk() } }
		post("/api/chapters/umd/projects/rise-dc/status?draft=$draft", """{"status":"active"}""").andExpect { status { isOk() } }
	}

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		mail.sent.clear()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", "lead")
		person("Vic", "viewer")
		ada = person("Ada")
		bo = person("Bo")
	}

	@Test
	fun `a semester plan stays in the draft, previews its access, and releases in one go`() {
		val draft = newDraft()
		plan(draft)
		assertEquals(0, dsl.fetchCount(PROJECT)) // nothing live yet
		get("/api/chapters/umd/projects").andExpect { jsonPath("$.length()") { value(0) } }
		get("/api/chapters/umd/projects?draft=$draft").andExpect {
			jsonPath("$[0].name") { value("RISE DC") }
			jsonPath("$[0].status") { value("active") }
			jsonPath("$[0].pendingChanges") { value(4) }
		}
		get("/api/chapters/umd/projects/rise-dc?draft=$draft").andExpect { jsonPath("$.members.length()") { value(2) } }
		// PRD: drafts are for the chapter's leads and national only.
		get("/api/chapters/umd/drafts", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
		get("/api/chapters/umd/projects?draft=$draft", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }

		val detail = body(get("/api/chapters/umd/drafts/$draft"))
		assertEquals(listOf("Create RISE DC", "Add Ada Test to RISE DC", "Add Bo Test to RISE DC", "Start RISE DC"), detail.path("changes").toList().map { it.path("summary").asString() })
		assertEquals(0, detail.path("conflicts").size())
		with(detail.path("preview")) {
			assertEquals(2, path("people").asInt())
			assertEquals(4, path("adds").asInt()) // Slack and the Google group, for both
			assertEquals(4, path("afterAgreement").asInt()) // GitHub and Vaultwarden wait for the project agreement
			val adaChange = path("perPerson").toList().single { it.path("name").asString() == "Ada Test" }
			assertEquals(listOf("RISE DC"), adaChange.path("joins").toList().map { it.asString() })
			assertEquals(listOf("umd-rise-dc (Slack)", "umd-rise-dc@hack4impact.org (Google group)"), adaChange.path("gains").toList().map { it.asString() })
		}

		post("/api/chapters/umd/drafts/$draft/release", "{}").andExpect {
			jsonPath("$.released") { value(true) }
			jsonPath("$.preview.people") { value(2) }
		}
		with(dsl.selectFrom(PROJECT).fetchSingle()) {
			assertEquals("active", status)
			assertEquals(2, dsl.fetchCount(PROJECT_MEMBER, PROJECT_MEMBER.PROJECT_ID.eq(id), PROJECT_MEMBER.REMOVED_AT.isNull))
		}
		with(dsl.selectFrom(CHANGESET).fetchSingle()) {
			assertEquals("released", status)
			assertEquals(dsl.select(PERSON.ID).from(PERSON).where(PERSON.FIRST_NAME.eq("Lena")).fetchSingle().value1(), releasedBy)
		}
		assertTrue(dsl.fetchExists(AUDIT_EVENT, AUDIT_EVENT.ACTION.eq("draft.release")))
		assertTrue(dsl.fetchCount(dsl.selectFrom("portal.event_publication").where("serialized_event LIKE '%\"trigger\":\"release\"%'")) > 0) // the sync, queued in the same transaction
		val until = System.currentTimeMillis() + 10_000
		while (mail.sent.size < 2 && System.currentTimeMillis() < until) Thread.sleep(100)
		val summary = mail.sent.single { it.to == "ada@hack4impact.org" }
		assertTrue("You're on: RISE DC" in summary.text && "umd-rise-dc (Slack)" in summary.text && "Once you sign the project agreement" in summary.text)
		post("/api/chapters/umd/projects/rise-dc/members?draft=$draft", """{"personId":"$ada"}""").andExpect { status { isConflict() } } // released: closed for changes
	}

	@Test
	fun `a change that no longer works is a conflict, and nothing is released until it's fixed`() {
		dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "Old Site").set(PROJECT.SLUG, "old-site").set(PROJECT.STATUS, "active").execute()
		val draft = newDraft()
		mvc.put("/api/chapters/umd/projects/old-site?draft=$draft") { with(csrf()); with(signedIn(lead)); contentType = MediaType.APPLICATION_JSON; content = """{"name":"Old Site 2"}""" }
			.andExpect { status { isOk() } }
		post("/api/chapters/umd/projects/old-site/members?draft=$draft", """{"personId":"$ada"}""").andExpect { status { isOk() } }
		// Not a member of the chapter: refused at once, not recorded.
		val stranger = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Zed").set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active").returningResult(PERSON.ID).fetchSingle().value1()
		post("/api/chapters/umd/projects/old-site/members?draft=$draft", """{"personId":"$stranger"}""").andExpect { status { isBadRequest() } }
		assertEquals(2, dsl.fetchCount(CHANGE))

		// Live data moves on: Ada leaves the chapter.
		dsl.update(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.LEFT_ON, java.time.LocalDate.now()).where(CHAPTER_MEMBERSHIP.PERSON_ID.eq(ada)).execute()
		get("/api/chapters/umd/drafts/$draft").andExpect {
			jsonPath("$.conflicts.length()") { value(1) }
			jsonPath("$.conflicts[0].summary") { value("Add Ada Test to Old Site 2") }
			jsonPath("$.conflicts[0].problem") { value("That person isn't a member of this chapter") }
		}
		post("/api/chapters/umd/drafts/$draft/release", "{}").andExpect {
			jsonPath("$.released") { value(false) }
			jsonPath("$.conflicts.length()") { value(1) }
		}
		assertEquals("Old Site", dsl.select(PROJECT.NAME).from(PROJECT).fetchSingle().value1()) // one transaction: the rename didn't happen either
		assertEquals("draft", dsl.select(CHANGESET.STATUS).from(CHANGESET).fetchSingle().value1())

		val conflict = dsl.select(CHANGE.ID).from(CHANGE).where(CHANGE.OPERATION.eq("add_member")).fetchSingle().value1()
		mvc.delete("/api/chapters/umd/drafts/$draft/changes/$conflict") { with(csrf()); with(signedIn(lead)) }.andExpect { status { isOk() } }
		post("/api/chapters/umd/drafts/$draft/release", "{}").andExpect { jsonPath("$.released") { value(true) } }
		assertEquals("Old Site 2", dsl.select(PROJECT.NAME).from(PROJECT).fetchSingle().value1())
	}

	@Test
	fun `changes to a project the draft creates still match when replayed, including its standard resources`() {
		val draft = newDraft()
		plan(draft)
		val github = body(get("/api/chapters/umd/projects/rise-dc?draft=$draft")).path("resources").toList().single { it.path("tool").asString() == "github" }.path("resourceId").asString()
		mvc.delete("/api/chapters/umd/projects/rise-dc/resources/$github?draft=$draft") { with(csrf()); with(signedIn(lead)) }.andExpect { status { isOk() } }
		get("/api/chapters/umd/drafts/$draft").andExpect { jsonPath("$.conflicts.length()") { value(0) } }
		post("/api/chapters/umd/drafts/$draft/release", "{}").andExpect { jsonPath("$.released") { value(true) } }
		assertEquals(3, dsl.fetchCount(PROJECT_RESOURCE)) // Slack, Google, Vaultwarden: the GitHub team was detached
	}

	@Test
	fun `a scheduled draft releases when due, or goes back to being a draft and tells whoever scheduled it`() {
		val draft = newDraft()
		plan(draft)
		post("/api/chapters/umd/drafts/$draft/schedule", """{"at":"2020-01-01T00:00:00Z"}""").andExpect { status { isBadRequest() } }
		post("/api/chapters/umd/drafts/$draft/schedule", """{"at":"${OffsetDateTime.now().plusDays(1)}"}""").andExpect { jsonPath("$.draft.status") { value("scheduled") } }
		post("/api/chapters/umd/projects/rise-dc/members?draft=$draft", """{"personId":"$ada"}""").andExpect { status { isConflict() } } // unschedule first
		assertEquals(0, drafts.releaseDue().size) // not due yet
		dsl.update(CHANGESET).set(CHANGESET.SCHEDULED_FOR, OffsetDateTime.now().minusMinutes(1)).execute()
		assertTrue(drafts.releaseDue().single().second.released)
		assertEquals(1, dsl.fetchCount(PROJECT))

		val second = newDraft("Spring changes")
		post("/api/chapters/umd/projects/rise-dc/members?draft=$second", """{"personId":"${person("Cy")}"}""").andExpect { status { isOk() } }
		post("/api/chapters/umd/drafts/$second/schedule", """{"at":"${OffsetDateTime.now().plusDays(1)}"}""")
		dsl.update(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.LEFT_ON, java.time.LocalDate.now()).where(CHAPTER_MEMBERSHIP.PERSON_ID.eq(dsl.select(PERSON.ID).from(PERSON).where(PERSON.FIRST_NAME.eq("Cy")))).execute()
		dsl.update(CHANGESET).set(CHANGESET.SCHEDULED_FOR, OffsetDateTime.now().minusMinutes(1)).where(CHANGESET.NAME.eq("Spring changes")).execute()
		assertEquals(false, drafts.releaseDue().single().second.released)
		with(dsl.selectFrom(CHANGESET).where(CHANGESET.NAME.eq("Spring changes")).fetchSingle()) {
			assertEquals("draft", status)
			assertNull(scheduledFor)
			assertTrue(releaseError!!.contains("isn't a member of this chapter"))
		}
		val until = System.currentTimeMillis() + 10_000
		while (mail.sent.none { it.to == lead } && System.currentTimeMillis() < until) Thread.sleep(100)
		assertTrue(mail.sent.single { it.to == lead }.subject.contains("wasn't released"))
	}

	@Test
	fun `a discarded draft changes nothing`() {
		val draft = newDraft()
		plan(draft)
		post("/api/chapters/umd/drafts/$draft/discard", "{}").andExpect { jsonPath("$.draft.status") { value("discarded") } }
		assertEquals(0, dsl.fetchCount(PROJECT))
		post("/api/chapters/umd/drafts/$draft/release", "{}").andExpect { status { isConflict() } }
		get("/api/chapters/umd/drafts").andExpect { jsonPath("$[0].status") { value("discarded") } } // still listed, for the record
	}
}
