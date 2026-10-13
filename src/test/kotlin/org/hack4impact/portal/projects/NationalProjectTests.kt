package org.hack4impact.portal.projects

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.drafts.DraftFakes
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.resolver.Reason
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.sync.WorldLoader
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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Decision 141: national runs its own projects, with anyone in the org on the team. */
@Import(TestcontainersConfiguration::class, DraftFakes::class)
@SpringBootTest
@AutoConfigureMockMvc
class NationalProjectTests(@Autowired private val mvc: MockMvc, @Autowired private val dsl: DSLContext, @Autowired private val worlds: WorldLoader) {
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
	private lateinit var gt: UUID
	private lateinit var ada: UUID
	private lateinit var gil: UUID
	private val nora = "nora@hack4impact.org"
	private val lena = "lena@hack4impact.org"

	private fun signedIn(email: String) = oidcLogin().idToken { it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org") }

	private fun body(result: ResultActionsDsl): JsonNode = HttpJson.MAPPER.readTree(result.andReturn().response.contentAsString)

	private fun post(path: String, json: String, as_: String = nora): ResultActionsDsl = mvc.post(path) {
		with(csrf()); with(signedIn(as_)); contentType = MediaType.APPLICATION_JSON; content = json
	}

	private fun get(path: String, as_: String = nora) = mvc.get(path) { with(signedIn(as_)) }

	private fun person(first: String, chapter: UUID, status: String = "active", role: String? = null): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, status).set(PERSON.ORG_EMAIL, "${first.lowercase()}@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { id ->
				dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapter).execute()
				role?.let { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, id).set(CHAPTER_ROLE.CHAPTER_ID, chapter).set(CHAPTER_ROLE.ROLE, it).execute() }
			}

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		gt = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "gt").set(CHAPTER.NAME, "Hack4Impact GT").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", umd, role = "lead")
		ada = person("Ada", umd)
		gil = person("Gil", gt, status = "alumni")
		person("Nora", gt).also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
	}

	@Test
	fun `national builds a team from several chapters, and they all get the project's tools`() {
		get("/api/chapters/national").andExpect { status { isOk() }; jsonPath("$.kind") { value("national") } }
		post("/api/chapters/national/projects", """{"name":"Chapter Starter Kit"}""").andExpect { status { isCreated() } }

		// Anyone in the org, each with their chapter, to tell people apart.
		val candidates = body(get("/api/chapters/national/projects/chapter-starter-kit/candidates"))
		assertEquals(listOf("UMD"), candidates.single { it.path("name").asString() == "Ada Test" }.path("chapters").toList().map { it.asString() })
		assertEquals(listOf("GT"), candidates.single { it.path("name").asString() == "Gil Test" }.path("chapters").toList().map { it.asString() })

		post("/api/chapters/national/projects/chapter-starter-kit/members", """{"personId":"$ada"}""").andExpect { status { isOk() } }
		post("/api/chapters/national/projects/chapter-starter-kit/members", """{"personId":"$gil"}""").andExpect { status { isOk() } }
		post("/api/chapters/national/projects/chapter-starter-kit/status", """{"status":"active"}""").andExpect { status { isOk() } }

		// The resolver gives both of them the project's Slack channel, though neither is in the national chapter.
		val slack = dsl.select(RESOURCE.ID).from(RESOURCE).join(PROJECT_RESOURCE).on(PROJECT_RESOURCE.RESOURCE_ID.eq(RESOURCE.ID))
			.where(RESOURCE.TOOL.eq("slack"), RESOURCE.NAME.eq("national-chapter-starter-kit")).fetchSingle().value1()!!
		val grants = Resolver.resolve(worlds.load().world).grants.filter { it.resourceId == slack }
		assertEquals(setOf(ada, gil), grants.map { it.personId }.toSet())
		assertTrue(grants.all { g -> g.reasons.single() is Reason.ProjectMember })
		get("/api/chapters/national").andExpect { jsonPath("$.stats.activeMembers") { value(1) }; jsonPath("$.stats.alumni") { value(1) } }
	}

	@Test
	fun `only national runs it, nobody applies to it, and chapter projects stay chapter-only`() {
		post("/api/chapters/national/projects", """{"name":"Chapter Starter Kit"}""").andExpect { status { isCreated() } }
		// A chapter lead has no role in national's chapter.
		get("/api/chapters/national/projects", as_ = lena).andExpect { status { isForbidden() } }
		post("/api/chapters/national/projects", """{"name":"Mine"}""", as_ = lena).andExpect { status { isForbidden() } }
		// No join page and no roster.
		mvc.get("/api/apply/chapters/national").andExpect { status { isNotFound() } }
		post("/api/chapters/national/roster", """{"csv":"Ada,Test,ada@umd.edu"}""").andExpect { status { isBadRequest() } }
		// A chapter's own project still only takes its members.
		post("/api/chapters/umd/projects", """{"name":"RISE DC"}""").andExpect { status { isCreated() } }
		post("/api/chapters/umd/projects/rise-dc/members", """{"personId":"$gil"}""").andExpect { status { isBadRequest() } }
	}
}
