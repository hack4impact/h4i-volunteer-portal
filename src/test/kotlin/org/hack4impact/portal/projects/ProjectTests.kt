package org.hack4impact.portal.projects

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.DISCOVERED_RESOURCE
import org.hack4impact.portal.db.tables.references.EVENT_PUBLICATION
import org.hack4impact.portal.db.tables.references.INSTITUTION
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.emptyPortalTables
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Build plan step 8: a lead sets up a project and sees its planned changes in dry run. */
@Import(TestcontainersConfiguration::class, FakeTools::class)
@SpringBootTest
@AutoConfigureMockMvc
class ProjectTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val engine: SyncEngine,
	@Autowired private val slack: InMemoryReadAdapter,
	@Autowired private val github: InMemoryReadAdapter,
) {
	private val json = JsonMapper.builder().build()
	private lateinit var umd: UUID
	private lateinit var gt: UUID
	private lateinit var ada: UUID
	private lateinit var alan: UUID
	private lateinit var applicant: UUID
	private lateinit var gus: UUID
	private lateinit var developer: UUID
	private lateinit var techLead: UUID
	private lateinit var fall: UUID

	private fun person(first: String, chapter: UUID, status: String = "active", email: String? = null): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, status).set(PERSON.ORG_EMAIL, email)
			.returningResult(PERSON.ID).fetchSingle().value1()!!
			.also { dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, it).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapter).execute() }

	private fun role(person: UUID, chapter: UUID, role: String) =
		dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, person).set(CHAPTER_ROLE.CHAPTER_ID, chapter).set(CHAPTER_ROLE.ROLE, role).execute()

	private fun signedIn(email: String) = oidcLogin().idToken {
		it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	private fun send(method: String, path: String, body: String? = null, as_: String = "lena@hack4impact.org"): ResultActionsDsl {
		val setup: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
			with(signedIn(as_)); with(csrf())
			if (body != null) { contentType = MediaType.APPLICATION_JSON; content = body }
		}
		return when (method) {
			"POST" -> mvc.post(path) { setup() }
			"PUT" -> mvc.put(path) { setup() }
			"DELETE" -> mvc.delete(path) { setup() }
			else -> mvc.get(path) { setup() }
		}
	}

	private fun body(result: ResultActionsDsl): JsonNode = json.readTree(result.andReturn().response.contentAsString)

	private fun create(name: String = "RISE DC", extra: String = ""): JsonNode =
		body(send("POST", "/api/chapters/umd/projects", """{"name":"$name","tags":["lts"],"termId":"$fall","partner":"RISE DC Inc"$extra}""").andExpect { status { isCreated() } })

	private fun resource(project: JsonNode, tool: String) = project.path("resources").first { it.path("tool").asString() == tool }

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		val school = dsl.insertInto(INSTITUTION).set(INSTITUTION.NAME, "University of Maryland").returningResult(INSTITUTION.ID).fetchSingle().value1()!!
		fall = dsl.insertInto(TERM).set(TERM.INSTITUTION_ID, school).set(TERM.SEASON, "fall").set(TERM.YEAR, 2026.toShort())
			.set(TERM.STARTS_ON, LocalDate.parse("2026-08-31")).set(TERM.ENDS_ON, LocalDate.parse("2026-12-20")).returningResult(TERM.ID).fetchSingle().value1()!!
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").set(CHAPTER.INSTITUTION_ID, school).returningResult(CHAPTER.ID).fetchSingle().value1()!!
		gt = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "gt").set(CHAPTER.NAME, "Georgia Tech").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", umd, email = "lena@hack4impact.org").also { role(it, umd, "lead") }
		person("Vic", umd, email = "vic@hack4impact.org").also { role(it, umd, "viewer") }
		ada = person("Ada", umd)
		alan = person("Alan", umd, status = "alumni")
		applicant = person("Apu", umd, status = "applicant")
		gus = person("Gus", gt, email = "gus@hack4impact.org").also { role(it, gt, "lead") }
		person("Nora", gt, email = "nora@hack4impact.org").also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		developer = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Developer").returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		techLead = dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, "Tech Lead").set(PROJECT_ROLE.IS_LEAD, true).returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!
		slack.failure = null; slack.accounts = emptyList(); slack.resources = emptyMap()
		github.failure = null; github.accounts = emptyList(); github.resources = emptyMap()
	}

	@Test
	fun `a lead sets up a project and its planned changes show after a dry run`() {
		val created = create()
		assertEquals("draft", created.path("status").asString())
		assertEquals("rise-dc", created.path("slug").asString())
		assertEquals("Fall 2026", created.path("term").asString())
		// The national template's standard resources, named by the convention.
		assertEquals(
			setOf("slack:umd-rise-dc", "google:umd-rise-dc@hack4impact.org", "github:umd-rise-dc", "vaultwarden:umd-rise-dc"),
			created.path("resources").toList().map { "${it.path("tool").asString()}:${it.path("name").asString()}" }.toSet(),
		)
		assertTrue(resource(created, "github").path("requiresAgreement").asBoolean())
		assertTrue(created.path("resources").all { it.path("state").asString() == "to_create" })

		send("POST", "/api/chapters/umd/projects/rise-dc/members", """{"personId":"$ada","roleId":"$techLead"}""").andExpect { status { isOk() } }
		val withAlan = body(send("POST", "/api/chapters/umd/projects/rise-dc/members", """{"personId":"$alan","roleId":"$developer"}""").andExpect { status { isOk() } })
		// A draft is previewed as if active: both get Slack; the gated GitHub team waits on their agreements.
		assertTrue(withAlan.path("preview").path("asIfActive").asBoolean())
		assertEquals(2, resource(withAlan, "slack").path("getting").asInt())
		assertEquals(0, resource(withAlan, "github").path("getting").asInt())
		assertEquals(2, resource(withAlan, "github").path("awaitingAgreement").asInt())
		assertEquals(0, withAlan.path("agreementsSigned").asInt())

		engine.dryRun("manual")
		assertEquals(0, body(send("GET", "/api/chapters/umd/projects/rise-dc")).path("planned").size()) // a draft changes nothing in the tools

		send("POST", "/api/chapters/umd/projects/rise-dc/status", """{"status":"active"}""").andExpect { status { isOk() } }
		engine.dryRun("manual") // what POST /api/chapters/umd/sync/run asks for through the outbox

		val after = body(send("GET", "/api/chapters/umd/projects/rise-dc"))
		val planned = after.path("planned").toList().map { "${it.path("kind").asString()}:${it.path("tool").asString()}:${it.path("person").asString("")}" }.toSet()
		assertEquals(
			setOf("create_resource:slack:", "add:slack:Ada Test", "add:slack:Alan Test", "create_resource:github:"),
			planned,
		)
		assertEquals(false, after.path("plannedOutOfDate").asBoolean())
		assertTrue(after.path("activity").any { it.path("action").asString() == "project.create" && it.path("actor").asString() == "Lena Test" })

		send("POST", "/api/chapters/umd/sync/run").andExpect { status { isAccepted() } }
		// The requested run happens in the background; wait for it so it doesn't overlap the next test.
		val deadline = System.currentTimeMillis() + 15_000
		while (dsl.fetchCount(EVENT_PUBLICATION, EVENT_PUBLICATION.COMPLETION_DATE.isNull) > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100)
		assertEquals(0, dsl.fetchCount(EVENT_PUBLICATION, EVENT_PUBLICATION.COMPLETION_DATE.isNull))
	}

	@Test
	fun `only leads, co-leads and national change projects, and only in their chapter`() {
		send("POST", "/api/chapters/umd/projects", """{"name":"X"}""", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
		send("POST", "/api/chapters/umd/projects", """{"name":"X"}""", as_ = "gus@hack4impact.org").andExpect { status { isForbidden() } }
		send("POST", "/api/chapters/umd/projects", """{"name":"X"}""", as_ = "nora@hack4impact.org").andExpect { status { isCreated() } }
		send("GET", "/api/chapters/umd/projects/x", as_ = "vic@hack4impact.org").andExpect {
			status { isOk() }
			jsonPath("$.canEdit") { value(false) }
		}
		send("GET", "/api/chapters/umd/projects", as_ = "gus@hack4impact.org").andExpect { status { isForbidden() } }
		send("POST", "/api/chapters/umd/sync/run", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
	}

	@Test
	fun `members must be current chapter members who are active or alumni, once each`() {
		create()
		val path = "/api/chapters/umd/projects/rise-dc/members"
		send("POST", path, """{"personId":"$applicant"}""").andExpect { status { isBadRequest() } }
		send("POST", path, """{"personId":"$gus"}""").andExpect { status { isBadRequest() } } // Georgia Tech
		send("POST", path, """{"personId":"$ada"}""").andExpect { status { isOk() } }
		send("POST", path, """{"personId":"$ada"}""").andExpect { status { isConflict() } }
		send("PUT", "$path/$ada", """{"roleId":"$techLead"}""").andExpect { jsonPath("$.members[0].isLead") { value(true) } }
		send("DELETE", "$path/$ada").andExpect { jsonPath("$.members.length()") { value(0) } }
		assertNotNull(dsl.select(PROJECT_MEMBER.REMOVED_AT).from(PROJECT_MEMBER).where(PROJECT_MEMBER.PERSON_ID.eq(ada)).fetchSingle().value1())
	}

	@Test
	fun `resources are attached new or shared, checked, and a never-created one is dropped when detached`() {
		create()
		create("Food Bank")
		val path = "/api/chapters/umd/projects/rise-dc/resources"
		send("POST", path, """{"tool":"slack","name":"#UMD Rise Dc Design"}""").andExpect { status { isBadRequest() } } // spaces
		send("POST", path, """{"tool":"slack","name":"umd-food-bank"}""").andExpect { status { isConflict() } } // exists: attach it instead
		send("POST", path, """{"tool":"notion","name":"x"}""").andExpect { status { isBadRequest() } }
		send("POST", path, """{"tool":"github","name":"umd-rise-dc-design","audience":"role"}""").andExpect { status { isBadRequest() } } // role missing

		val design = body(send("POST", path, """{"tool":"slack","name":"#umd-rise-dc-design","audience":"role","roleId":"$developer","tags":["Design"]}""").andExpect { status { isOk() } })
		val row = design.path("resources").first { it.path("name").asString() == "umd-rise-dc-design" }
		assertEquals("Developer", row.path("role").asString())
		assertEquals("design", row.path("tags")[0].asString())

		val foodBankSlack = dsl.select(RESOURCE.ID).from(RESOURCE).where(RESOURCE.NAME.eq("umd-food-bank"), RESOURCE.TOOL.eq("slack")).fetchSingle().value1()!!
		val shared = body(send("POST", path, """{"resourceId":"$foodBankSlack","audience":"leads","access":"read"}""").andExpect { status { isOk() } })
		assertEquals(listOf("Food Bank"), shared.path("resources").first { it.path("resourceId").asString() == "$foodBankSlack" }.path("sharedWith").toList().map { it.asString() })

		val designId = row.path("resourceId").asString()
		send("DELETE", "$path/$designId").andExpect { status { isOk() } }
		assertNotNull(dsl.select(RESOURCE.ARCHIVED_AT).from(RESOURCE).where(RESOURCE.ID.eq(UUID.fromString(designId))).fetchSingle().value1())
		send("DELETE", "$path/$foodBankSlack").andExpect { status { isOk() } }
		assertEquals(null, dsl.select(RESOURCE.ARCHIVED_AT).from(RESOURCE).where(RESOURCE.ID.eq(foodBankSlack)).fetchSingle().value1()) // Food Bank still uses it
	}

	@Test
	fun `a resource whose name the adoption scan already found in the tool is flagged`() {
		dsl.insertInto(DISCOVERED_RESOURCE).set(DISCOVERED_RESOURCE.TOOL, "slack").set(DISCOVERED_RESOURCE.EXTERNAL_ID, "C1").set(DISCOVERED_RESOURCE.NAME, "umd-rise-dc").execute()
		val created = create()
		assertTrue(resource(created, "slack").path("existsInTool").asBoolean())
		assertEquals(false, resource(created, "github").path("existsInTool").asBoolean())
	}

	@Test
	fun `status moves draft, active, paused, closed, and a closed project can't change`() {
		create()
		val path = "/api/chapters/umd/projects/rise-dc/status"
		send("POST", path, """{"status":"paused"}""").andExpect { status { isConflict() } }
		send("POST", path, """{"status":"active"}""").andExpect { jsonPath("$.status") { value("active") } }
		send("POST", path, """{"status":"draft"}""").andExpect { status { isConflict() } }
		send("POST", path, """{"status":"paused"}""").andExpect { jsonPath("$.status") { value("paused") } }
		send("POST", path, """{"status":"closed"}""").andExpect { jsonPath("$.status") { value("closed") } }
		send("POST", "/api/chapters/umd/projects/rise-dc/members", """{"personId":"$ada"}""").andExpect { status { isConflict() } }
		send("POST", "/api/chapters/umd/projects", """{"name":"RISE DC"}""").andExpect { status { isConflict() } } // the short name is taken
		assertEquals(4, dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.TARGET_TYPE.eq("project"))) // create and three status changes; refused changes leave no trace
	}

	@Test
	fun `a project's Notion page is placed by the chapter's routes`() {
		val root = "11111111111111111111111111111111"
		val lts = "22222222222222222222222222222222"
		assertEquals("not_set_up", create().path("notion").path("status").asString())
		send("PUT", "/api/chapters/umd/notion", """{"defaultParent":"$root","titlePattern":"{project} ({semester})","routes":[{"kind":"tag","value":"LTS","parent":"https://www.notion.so/h4i/Long-Term-$lts"}]}""")
			.andExpect {
				status { isOk() }
				jsonPath("$.configured") { value(false) } // no integration in tests, so pages aren't checked
				jsonPath("$.routes[0].value") { value("lts") }
			}
		send("GET", "/api/chapters/umd/projects/rise-dc").andExpect {
			jsonPath("$.notion.status") { value("planned") }
			jsonPath("$.notion.title") { value("RISE DC (Fall 2026)") }
			jsonPath("$.notion.parentPageId") { value("22222222-2222-2222-2222-222222222222") }
			jsonPath("$.notion.matchedBy") { value("tag lts") }
		}
		send("PUT", "/api/chapters/umd/notion", """{"titlePattern":"no placeholder"}""").andExpect { status { isBadRequest() } }
		send("PUT", "/api/chapters/umd/notion", """{"titlePattern":"{project}","defaultParent":"not a link"}""").andExpect { status { isBadRequest() } }
		send("PUT", "/api/chapters/umd/notion", """{"titlePattern":"{project}"}""", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
	}
}
