package org.hack4impact.portal.rules

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.RULE
import org.hack4impact.portal.drafts.DraftFakes
import org.hack4impact.portal.emptyPortalTables
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Build plan step 14: rules a lead can read, preview, change directly or in a draft, and turn off again. */
@Import(TestcontainersConfiguration::class, DraftFakes::class)
@SpringBootTest
@AutoConfigureMockMvc
class RuleTests(@Autowired private val mvc: MockMvc, @Autowired private val dsl: DSLContext) {
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
	private lateinit var general: UUID
	private lateinit var gtRepo: UUID
	private val lead = "lena@hack4impact.org"
	private val viewer = "vic@hack4impact.org"
	private val national = "nora@hack4impact.org"

	private fun signedIn(email: String) = oidcLogin().idToken { it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org") }

	private fun body(result: ResultActionsDsl): JsonNode = HttpJson.MAPPER.readTree(result.andReturn().response.contentAsString)

	private fun post(path: String, json: String, as_: String = lead): ResultActionsDsl = mvc.post(path) {
		with(csrf()); with(signedIn(as_)); contentType = MediaType.APPLICATION_JSON; content = json
	}

	private fun put(path: String, json: String, as_: String = lead): ResultActionsDsl = mvc.put(path) {
		with(csrf()); with(signedIn(as_)); contentType = MediaType.APPLICATION_JSON; content = json
	}

	private fun get(path: String, as_: String = lead) = mvc.get(path) { with(signedIn(as_)) }

	private fun person(first: String, chapter: UUID, role: String? = null, title: String? = null): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "${first.lowercase()}@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { id ->
				dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapter).execute()
				role?.let { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, id).set(CHAPTER_ROLE.CHAPTER_ID, chapter).set(CHAPTER_ROLE.ROLE, it).set(CHAPTER_ROLE.TITLE, title).execute() }
			}

	private fun resource(tool: String, name: String, chapter: UUID?, vararg tags: String): UUID =
		dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, tool).set(RESOURCE.NAME, name).set(RESOURCE.CHAPTER_ID, chapter).set(RESOURCE.TAGS, arrayOf(*tags))
			.returningResult(RESOURCE.ID).fetchSingle().value1()!!

	/** Everyone in the chapter gets the general channel. */
	private val everyoneGeneral = """{"name":"Everyone in #general","who":{"everyone":true},"resources":{"scope":"tags","tags":["general"]},"access":"read"}"""

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		gt = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "gt").set(CHAPTER.NAME, "Hack4Impact GT").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", umd, "lead")
		person("Vic", umd, "viewer", title = "Director of Tech")
		ada = person("Ada", umd)
		person("Bo", umd)
		person("Gil", gt)
		person("Nora", gt).also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		general = resource("slack", "umd-general", umd, "general")
		resource("github", "umd-eng", umd, "eng")
		gtRepo = resource("github", "gt-eng", gt, "eng")
	}

	@Test
	fun `a lead's rule reads as a sentence, reaches the chapter's people, and is audited`() {
		val page = body(post("/api/chapters/umd/rules", everyoneGeneral).andExpect { status { isCreated() } })
		val rule = page.path("rules").single()
		assertEquals("Everyone → Read on resources tagged #general", rule.path("sentence").asString())
		assertEquals(4, rule.path("reach").path("people").asInt()) // Lena, Vic, Ada, Bo; not GT's people
		assertEquals(1, rule.path("reach").path("resources").asInt())
		assertTrue(page.path("canEdit").asBoolean())
		val audit = dsl.selectFrom(AUDIT_EVENT).where(AUDIT_EVENT.ACTION.eq("rule.create")).fetchSingle()
		assertEquals("rule", audit.targetType)
		assertTrue(audit.after!!.data().contains("Everyone → Read on resources tagged #general"))

		// Viewers read them; only leads, co-leads and national change them; other chapters can't see them.
		get("/api/chapters/umd/rules", as_ = viewer).andExpect { status { isOk() }; jsonPath("$.canEdit") { value(false) }; jsonPath("$.rules.length()") { value(1) } }
		post("/api/chapters/umd/rules", everyoneGeneral, as_ = viewer).andExpect { status { isForbidden() } }
		get("/api/chapters/umd/rules", as_ = "gil@hack4impact.org").andExpect { status { isForbidden() } }
	}

	@Test
	fun `a chapter's rule only reaches its own resources, even by a shared tag`() {
		val page = body(post("/api/chapters/umd/rules", """{"name":"Tech","who":{"titles":["Director of Tech"]},"resources":{"scope":"tags","tags":["eng"]},"access":"write"}"""))
		val rule = page.path("rules").single()
		assertEquals(1, rule.path("reach").path("people").asInt()) // Vic, by title
		assertEquals(1, rule.path("reach").path("resources").asInt()) // umd-eng, not gt-eng
		// Picking another chapter's resource by ID is refused outright.
		post("/api/chapters/umd/rules", """{"name":"Steal","who":{"everyone":true},"resources":{"scope":"ids","ids":["$gtRepo"]}}""").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `what can't work is refused in words, and what's merely odd is a warning`() {
		post("/api/chapters/umd/rules", """{"name":" ","who":{"everyone":true},"resources":{"scope":"tags","tags":["general"]}}""").andExpect {
			status { isBadRequest() }
		}
		post("/api/chapters/umd/rules", """{"name":"Nobody","resources":{"scope":"tags","tags":["general"]}}""").andExpect { status { isBadRequest() } }
		post("/api/chapters/umd/rules", """{"name":"Odd","who":{"everyone":true},"resources":{"scope":"tags","tags":["general"]},"access":"owner"}""").andExpect { status { isBadRequest() } }
		post("/api/chapters/umd/rules", """{"name":"Outsider","who":{"people":["${dsl.select(PERSON.ID).from(PERSON).where(PERSON.FIRST_NAME.eq("Gil")).fetchSingle().value1()}"]},"resources":{"scope":"tags","tags":["general"]}}""")
			.andExpect { status { isBadRequest() } }

		val preview = body(post("/api/chapters/umd/rules/preview", """{"input":{"name":"Typo","who":{"everyone":true},"resources":{"scope":"tags","tags":["#Genral"]},"access":"admin"}}"""))
		val warnings = preview.path("warnings").toList().map { it.asString() }
		assertTrue(warnings.any { "#genral" in it }, "$warnings")
		assertTrue(warnings.any { "no admin level" in it }, "$warnings")
		assertEquals(0, preview.path("preview").path("adds").asInt())
	}

	@Test
	fun `a preview shows exactly what saving would do, and saves nothing`() {
		val preview = body(post("/api/chapters/umd/rules/preview", """{"input":$everyoneGeneral}"""))
		assertEquals("Everyone → Read on resources tagged #general", preview.path("sentence").asString())
		assertEquals(4, preview.path("preview").path("people").asInt())
		assertEquals(4, preview.path("preview").path("adds").asInt())
		assertEquals(0, dsl.fetchCount(RULE))
		assertEquals(0, dsl.fetchCount(AUDIT_EVENT))
		post("/api/chapters/umd/rules/preview", """{"input":$everyoneGeneral}""", as_ = viewer).andExpect { status { isForbidden() } }

		// Turning it off takes back exactly what it gave.
		val id = body(post("/api/chapters/umd/rules", everyoneGeneral)).path("rules").single().path("id").asString()
		val off = body(post("/api/chapters/umd/rules/preview", """{"ruleId":"$id","input":${everyoneGeneral.dropLast(1)},"enabled":false}}"""))
		assertEquals(4, off.path("preview").path("removes").asInt())
		assertEquals(0, off.path("preview").path("adds").asInt())
		val gone = body(post("/api/chapters/umd/rules/preview", """{"ruleId":"$id","delete":true}"""))
		assertEquals(4, gone.path("preview").path("removes").asInt())
		assertEquals(1, dsl.fetchCount(RULE, RULE.DELETED_AT.isNull))
	}

	@Test
	fun `turning a rule off and deleting it keep the rule for the records`() {
		val id = body(post("/api/chapters/umd/rules", everyoneGeneral)).path("rules").single().path("id").asString()
		val off = body(post("/api/chapters/umd/rules/$id/enabled", """{"enabled":false}""")).path("rules").single()
		assertEquals(false, off.path("enabled").asBoolean())
		assertEquals(0, off.path("reach").path("people").asInt())
		put("/api/chapters/umd/rules/$id", everyoneGeneral.replace("Everyone in #general", "All of UMD in #general")).andExpect {
			status { isOk() }; jsonPath("$.rules[0].name") { value("All of UMD in #general") }; jsonPath("$.rules[0].reach.people") { value(4) }
		}
		mvc.delete("/api/chapters/umd/rules/$id") { with(csrf()); with(signedIn(lead)) }.andExpect { status { isOk() }; jsonPath("$.rules.length()") { value(0) } }
		val row = dsl.selectFrom(RULE).where(RULE.ID.eq(UUID.fromString(id))).fetchSingle()
		assertNotNull(row.deletedAt)
		assertEquals(listOf("rule.create", "rule.disable", "rule.update", "rule.delete"), dsl.select(AUDIT_EVENT.ACTION).from(AUDIT_EVENT).orderBy(AUDIT_EVENT.AT).fetch().map { it.value1() })
		// Another chapter's lead can't touch it, deleted or not.
		mvc.delete("/api/chapters/gt/rules/$id") { with(csrf()); with(signedIn(lead)) }.andExpect { status { isForbidden() } }
	}

	@Test
	fun `a rule in a draft waits for the release`() {
		val draft = body(post("/api/chapters/umd/drafts", """{"name":"Fall rules"}""")).path("id").asString()
		val inDraft = body(post("/api/chapters/umd/rules?draft=$draft", everyoneGeneral).andExpect { status { isCreated() } })
		assertEquals(listOf("Add the rule “Everyone in #general”"), inDraft.path("rules").single().path("pendingChanges").toList().map { it.asString() })
		assertEquals(0, dsl.fetchCount(RULE))
		get("/api/chapters/umd/rules").andExpect { jsonPath("$.rules.length()") { value(0) } }

		val detail = body(get("/api/chapters/umd/drafts/$draft"))
		val change = detail.path("changes").single()
		assertTrue(change.path("projectId").isNull)
		assertEquals(inDraft.path("rules").single().path("id").asString(), change.path("ruleId").asString())
		assertEquals(4, detail.path("preview").path("adds").asInt())

		post("/api/chapters/umd/drafts/$draft/release", "{}").andExpect { status { isOk() }; jsonPath("$.released") { value(true) } }
		get("/api/chapters/umd/rules").andExpect { jsonPath("$.rules[0].name") { value("Everyone in #general") }; jsonPath("$.rules[0].reach.people") { value(4) } }
	}

	@Test
	fun `national defaults are national's to change, and every chapter sees them`() {
		val nationalRule = """{"name":"Leads get the leads channel","who":{"chapterRoles":["lead","co_lead"]},"resources":{"scope":"tags","tags":["leads"]}}"""
		resource("slack", "chapter-leads", null, "leads")
		post("/api/national/rules", nationalRule).andExpect { status { isForbidden() } }
		get("/api/national/rules").andExpect { status { isForbidden() } }
		post("/api/national/rules", nationalRule, as_ = national).andExpect { status { isCreated() } }
		get("/api/chapters/umd/rules", as_ = viewer).andExpect {
			jsonPath("$.rules.length()") { value(0) }
			jsonPath("$.national[0].sentence") { value("Chapter leads and co-leads → Read on resources tagged #leads") }
			jsonPath("$.national[0].chapterCode") { doesNotExist() }
		}
		// National defaults can't name people.
		post("/api/national/rules", """{"name":"Ada","who":{"people":["$ada"]},"resources":{"scope":"tags","tags":["leads"]}}""", as_ = national).andExpect { status { isBadRequest() } }
	}

	@Test
	fun `a member's access says why they have it`() {
		post("/api/chapters/umd/rules", everyoneGeneral).andExpect { status { isCreated() } }
		val access = body(get("/api/chapters/umd/members/$ada/access", as_ = viewer))
		val slack = access.single { it.path("name").asString() == "umd-general" }
		assertEquals(listOf("Rule “Everyone in #general”"), slack.path("reasons").toList().map { it.asString() })
		get("/api/chapters/gt/members/$ada/access", as_ = national).andExpect { status { isNotFound() } }
	}
}
