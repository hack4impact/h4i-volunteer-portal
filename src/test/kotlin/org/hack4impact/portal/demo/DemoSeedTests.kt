package org.hack4impact.portal.demo

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.db.tables.references.CHANGESET
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.hack4impact.portal.drafts.Drafts
import org.hack4impact.portal.emptyPortalTables
import org.jooq.DSLContext
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The staging demo: seeded into an empty database, each persona sees their view (member, project lead, viewer, lead, national). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class DemoSeedTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val seed: DemoSeed,
	@Autowired private val drafts: Drafts,
) {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun personas(registry: DynamicPropertyRegistry) {
			registry.add("portal.demo.personas.national") { "nadia.test@hack4impact.org" }
			registry.add("portal.demo.personas.lead") { "leo.test@hack4impact.org" }
			registry.add("portal.demo.personas.co-lead") { "chloe.test@hack4impact.org" }
			registry.add("portal.demo.personas.viewer") { "Victor.Test@hack4impact.org" }
			registry.add("portal.demo.personas.project-lead") { "priya.test@hack4impact.org" }
			registry.add("portal.demo.personas.member") { "marcus.test@hack4impact.org" }
			registry.add("portal.sync.on-request") { "false" }
			registry.add("portal.agreements.on-request") { "false" }
		}
	}

	private fun signedIn(email: String) = oidcLogin().idToken { it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org") }
	private fun get(path: String, as_: String): JsonNode = HttpJson.MAPPER.readTree(mvc.get(path) { with(signedIn(as_)) }.andExpect { status { isOk() } }.andReturn().response.contentAsString)

	@Test
	fun `the demo seeds an empty database and every persona sees their own view`() {
		dsl.emptyPortalTables()
		seed.seed()
		assertEquals(2, dsl.fetchCount(CHAPTER))
		assertEquals(setOf("active", "paused", "closed", "draft"), dsl.select(PROJECT.STATUS).from(PROJECT).fetch().map { it.value1() }.toSet())
		assertEquals(3, dsl.fetchCount(REGISTRATION, REGISTRATION.STATUS.eq("submitted")))
		val draft = dsl.selectFrom(CHANGESET).fetchSingle()
		val chapter = dsl.select(CHAPTER.ID).from(CHAPTER).where(CHAPTER.CODE.eq("demo")).fetchSingle().value1()!!
		val leo = org.hack4impact.portal.auth.Viewer(dsl.select(PERSON.ID).from(PERSON).where(PERSON.FIRST_NAME.eq("Leo")).fetchSingle().value1(), "", null, false, mapOf(chapter to "lead"))
		with(drafts.detail(chapter, draft.id!!, leo)) {
			assertEquals(5, changes.size)
			assertEquals(0, conflicts.size)
			assertEquals(7, preview.people) // the new team, and the other tech leads (a rule: admin on every live project's repositories)
		}
		assertFailsWith<IllegalStateException> { seed.seed() } // not again over existing data

		// National: both chapters.
		assertEquals(listOf("demo", "sample"), get("/api/me", "nadia.test@hack4impact.org").path("chapters").toList().map { it.path("code").asString() })
		// The viewer (address matched without regard to case): the chapter, read-only.
		assertEquals("viewer", get("/api/chapters/demo", "victor.test@hack4impact.org").path("role").asString())
		assertEquals(false, get("/api/chapters/demo/projects/food-rescue-network", "victor.test@hack4impact.org").path("canEditTeam").asBoolean())

		// The member: their projects, access with reasons, the agreement still to sign.
		val home = get("/api/me/home", "marcus.test@hack4impact.org")
		assertEquals(listOf("Food Rescue Network", "Tutor Match"), home.path("projects").toList().map { it.path("name").asString() })
		val general = home.path("access").toList().single { it.path("name").asString() == "demo-general" }
		assertEquals("ready", general.path("state").asString())
		assertEquals("Member of Hack4Impact Demo University", general.path("reasons")[0].asString())
		assertTrue(home.path("access").toList().any { it.path("state").asString() == "after_agreement" && it.path("name").asString() == "demo-tutor-match" })
		assertEquals(setOf("signed", "sent"), home.path("agreements").toList().map { it.path("status").asString() }.toSet())
		assertEquals(0, get("/api/me", "marcus.test@hack4impact.org").path("chapters").size()) // no chapter role
		mvc.get("/api/chapters/demo/projects/food-rescue-network") { with(signedIn("marcus.test@hack4impact.org")) }.andExpect { status { isForbidden() } }

		// The project lead: their project's team only.
		val priya = "priya.test@hack4impact.org"
		with(get("/api/chapters/demo/projects/food-rescue-network", priya)) {
			assertEquals(false, path("canEdit").asBoolean())
			assertEquals(true, path("canEditTeam").asBoolean())
		}
		val newcomer = get("/api/chapters/demo/projects/food-rescue-network/candidates", priya).toList().first { it.path("name").asString() == "Grace Lee" }.path("personId").asString()
		mvc.post("/api/chapters/demo/projects/food-rescue-network/members") { with(csrf()); with(signedIn(priya)); contentType = MediaType.APPLICATION_JSON; content = """{"personId":"$newcomer"}""" }
			.andExpect { status { isOk() } }
		mvc.post("/api/chapters/demo/projects/food-rescue-network/resources") { with(csrf()); with(signedIn(priya)); contentType = MediaType.APPLICATION_JSON; content = """{"tool":"slack","name":"demo-extra"}""" }
			.andExpect { status { isForbidden() } } // not resources
		mvc.post("/api/chapters/demo/projects/tutor-match/members") { with(csrf()); with(signedIn(priya)); contentType = MediaType.APPLICATION_JSON; content = """{"personId":"$newcomer"}""" }
			.andExpect { status { isForbidden() } } // not a project she doesn't lead
		assertTrue(get("/api/me/home", priya).path("projects").toList().single().path("canOpen").asBoolean())

		// Automations: three chapter rules (one off) and a national default; the viewer reads them, and sees why the advisor has the leads channel.
		with(get("/api/chapters/demo/rules", "victor.test@hack4impact.org")) {
			assertEquals(listOf(true, true, false), path("rules").toList().map { it.path("enabled").asBoolean() })
			assertTrue(path("rules").toList().take(2).all { it.path("reach").path("people").asInt() > 0 })
			assertEquals(1, path("national").size())
			assertEquals(false, path("canEdit").asBoolean())
		}
		val victor = get("/api/chapters/demo/members", "victor.test@hack4impact.org").toList().single { it.path("name").asString() == "Victor Hughes" }.path("personId").asString()
		val leads = get("/api/chapters/demo/members/$victor/access", "victor.test@hack4impact.org").toList().single { it.path("name").asString() == "demo-leads" }
		assertEquals(listOf("Rule “Our advisor follows the leads channel”"), leads.path("reasons").toList().map { it.asString() })
	}
}
