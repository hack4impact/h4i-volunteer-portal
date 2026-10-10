package org.hack4impact.portal.adoption

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.Unavailable
import org.hack4impact.portal.db.tables.references.ADOPTION_SNAPSHOT
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.DISCOVERED_RESOURCE
import org.hack4impact.portal.db.tables.references.DISCOVERY_SCAN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.sync.FakeTools
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Build plan step 7: discovery, naming-convention matching, manual linking, grandfather snapshot and the adoption report. */
@Import(TestcontainersConfiguration::class, FakeTools::class)
@SpringBootTest
@AutoConfigureMockMvc
class AdoptionTests(
	@Autowired private val dsl: DSLContext,
	@Autowired private val scanner: AdoptionScanner,
	@Autowired private val reports: AdoptionReports,
	@Autowired private val mvc: MockMvc,
	@Autowired private val slack: InMemoryReadAdapter,
	@Autowired private val github: InMemoryReadAdapter,
) {
	private lateinit var umd: UUID
	private lateinit var gt: UUID
	private lateinit var lena: UUID
	private lateinit var ada: UUID
	private lateinit var alan: UUID
	private lateinit var rise: UUID

	private fun person(first: String, chapter: UUID, status: String = "active", orgEmail: String? = null, personal: String? = null): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, status)
			.set(PERSON.ORG_EMAIL, orgEmail).set(PERSON.PERSONAL_EMAIL, personal)
			.returningResult(PERSON.ID).fetchSingle().value1()!!
			.also { dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, it).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, chapter).execute() }

	private fun role(person: UUID, chapter: UUID, role: String) =
		dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, person).set(CHAPTER_ROLE.CHAPTER_ID, chapter).set(CHAPTER_ROLE.ROLE, role).execute()

	private fun signedIn(email: String) = oidcLogin().idToken {
		it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	private fun channel(id: String, name: String, vararg members: String, archived: Boolean = false) =
		ToolResource(id, name, archived) to members.map { ResourceMember(it, Access.WRITE) }

	private fun discovered(tool: String, externalId: String) =
		dsl.selectFrom(DISCOVERED_RESOURCE).where(DISCOVERED_RESOURCE.TOOL.eq(tool), DISCOVERED_RESOURCE.EXTERNAL_ID.eq(externalId)).fetchSingle()

	private fun resource(name: String) = reports.report(umd, true).resources.single { it.name == name }

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		gt = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "gt").set(CHAPTER.NAME, "Georgia Tech").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		lena = person("Lena", umd, orgEmail = "lena@hack4impact.org").also { role(it, umd, "lead") }
		ada = person("Ada", umd)
		alan = person("Alan", umd, status = "alumni", personal = "alan@personal.test")
		person("Rudy", umd)
		person("Vic", umd, orgEmail = "vic@hack4impact.org").also { role(it, umd, "viewer") }
		person("Gus", gt, orgEmail = "gus@hack4impact.org").also { role(it, gt, "lead") }
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, lena).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, "U-lena").execute()
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, ada).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, "U-ada").execute()
		// GitHub from the member DB: a login, no numeric ID (wiki decision 46).
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, ada).set(TOOL_ACCOUNT.TOOL, "github").set(TOOL_ACCOUNT.EXTERNAL_LOGIN, "ada-gh").execute()
		rise = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "RISE DC").set(PROJECT.SLUG, "rise-dc").set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, rise).set(PROJECT_MEMBER.PERSON_ID, ada).set(PROJECT_MEMBER.AGREEMENT_STATUS, "signed").execute()

		slack.failure = null
		github.failure = null
		// Alan isn't linked in Slack, but Slack shows the address he gave the member DB.
		slack.accounts = listOf(ToolAccount("U-alan", "alan", "alan@personal.test", null, AccountState.ACTIVE))
		slack.resources = mapOf(
			channel("C-umd", "umd", "U-lena", "U-ada", "U-alan", "U-stranger"),
			channel("C-rise", "umd-rise-dc", "U-ada", "U-alan"),
			channel("C-food", "umd-food-bank", "U-lena"),
			channel("C-gt", "gt-general", "U-gus"),
			channel("C-random", "random", "U-lena"),
			channel("C-old", "umd-old", "U-lena", archived = true),
		)
		github.accounts = emptyList()
		github.resources = mapOf(ToolResource("umd-rise-dc", "UMD RISE DC") to listOf(ResourceMember("1001", Access.ADMIN, "ada-gh")))
	}

	@Test
	fun `a scan discovers everything, matches names, and reads members only for the chosen chapter`() {
		val scans = scanner.scan(setOf("umd")).associateBy { it.tool }
		assertEquals(setOf(Tool.GITHUB, Tool.SLACK), scans.keys)
		with(scans.getValue(Tool.SLACK)) {
			assertEquals("completed", status)
			assertEquals(6, resourcesFound)
			assertEquals(5, matched)
			assertEquals(1, unmatched)
			assertEquals(3, snapshotted) // #umd, #umd-rise-dc, #umd-food-bank; not archived, not Georgia Tech's
		}
		assertEquals(1, scans.getValue(Tool.GITHUB).snapshotted)

		assertEquals("matched", discovered("slack", "C-gt").state)
		assertEquals(gt, discovered("slack", "C-gt").chapterId)
		assertNull(discovered("slack", "C-gt").snapshotScanId)
		assertEquals(0, dsl.fetchCount(ADOPTION_SNAPSHOT, ADOPTION_SNAPSHOT.DISCOVERED_RESOURCE_ID.eq(discovered("slack", "C-gt").id))) // another chapter's members are never read
		assertEquals("unmatched", discovered("slack", "C-random").state)
		assertEquals("food-bank", discovered("slack", "C-food").suggestedSlug)
	}

	@Test
	fun `the report says who is expected, grandfathered, unknown or missing for each resource`() {
		scanner.scan(setOf("umd"))
		with(resource("umd")) { // the chapter's own channel: every active member
			assertEquals("chapter_members", target)
			assertEquals(2, expected) // Lena, Ada
			assertEquals(1, grandfathered) // Alan, an alumnus, matched by email
			assertEquals(1, unknownAccounts)
			assertEquals(2, wouldAdd) // Rudy, Vic
		}
		with(resource("umd-rise-dc")) { // the Slack channel; the GitHub team is named "UMD RISE DC"
			assertEquals("project_team", target)
			assertEquals(rise, projectId)
			assertEquals(1, expected)
			assertEquals(1, grandfathered)
			assertEquals(0, wouldAdd)
		}
		val team = reports.report(umd, true).resources.single { it.tool == "github" }
		assertEquals(1, team.expected) // matched by GitHub login
		with(resource("umd-food-bank")) { // no project yet: nobody is expected, so Lena is grandfathered
			assertNull(target)
			assertEquals(1, grandfathered)
		}

		val people = reports.people(umd, resource("umd").id)!!.associateBy { it.name ?: it.login }
		assertEquals("grandfathered", people.getValue("Alan Test").verdict)
		assertEquals("email", people.getValue("Alan Test").matchedBy)
		assertEquals("unknown_account", people.getValue(null).verdict) // U-stranger: no person, no login
		assertEquals("would_add", people.getValue("Rudy Test").verdict)
		assertNull(reports.people(gt, resource("umd").id)) // not Georgia Tech's
	}

	@Test
	fun `a lead links a resource by hand, and the next scan keeps the decision`() {
		scanner.scan(setOf("umd"))
		val food = resource("umd-food-bank").id
		mvc.post("/api/chapters/umd/adoption/resources/$food") {
			with(signedIn("lena@hack4impact.org")); with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = """{"action":"link","target":"project_team","projectId":"$rise"}"""
		}.andExpect { status { isOk() } }

		scanner.scan(setOf("umd"))
		with(resource("umd-food-bank")) {
			assertEquals("linked", state)
			assertEquals("manual", matchMethod)
			assertEquals(rise, projectId)
			assertEquals(1, grandfathered) // Lena isn't on RISE DC
			assertEquals(1, wouldAdd) // Ada is
		}
		assertEquals(lena, discovered("slack", "C-food").linkedBy)
		assertEquals(1, dsl.fetchCount(AUDIT_EVENT, AUDIT_EVENT.ACTION.eq("adoption.decide"), AUDIT_EVENT.ACTOR_PERSON_ID.eq(lena)))

		mvc.post("/api/chapters/umd/adoption/resources/$food") {
			with(signedIn("lena@hack4impact.org")); with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = """{"action":"reset"}"""
		}.andExpect { status { isOk() } }
		assertEquals("matched", resource("umd-food-bank").state)
		assertEquals("convention", resource("umd-food-bank").matchMethod)
	}

	@Test
	fun `leads link unmatched resources to their chapter, but viewers and other chapters can't`() {
		scanner.scan(setOf("umd"))
		val random = discovered("slack", "C-random").id!!
		val gtChannel = discovered("slack", "C-gt").id!!
		fun decide(email: String, chapter: String, id: UUID, body: String) = mvc.post("/api/chapters/$chapter/adoption/resources/$id") {
			with(signedIn(email)); with(csrf())
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

		mvc.get("/api/adoption/unmatched") { with(signedIn("vic@hack4impact.org")) }.andExpect { status { isForbidden() } }
		mvc.get("/api/adoption/unmatched") { with(signedIn("lena@hack4impact.org")) }.andExpect {
			status { isOk() }
			jsonPath("$[0].name") { value("random") }
		}
		decide("vic@hack4impact.org", "umd", random, """{"action":"link","target":"chapter_members"}""").andExpect { status { isForbidden() } }
		decide("lena@hack4impact.org", "umd", gtChannel, """{"action":"unmanaged"}""").andExpect { status { isForbidden() } } // Georgia Tech's
		decide("gus@hack4impact.org", "umd", random, """{"action":"link","target":"chapter_members"}""").andExpect { status { isForbidden() } }
		decide("lena@hack4impact.org", "umd", random, """{"action":"link","target":"project_team"}""").andExpect { status { isBadRequest() } }
		decide("lena@hack4impact.org", "umd", random, """{"action":"link","target":"chapter_members"}""").andExpect {
			status { isOk() }
			jsonPath("$.resources[?(@.name == 'random')].state") { value("linked") }
		}
		assertEquals(umd, discovered("slack", "C-random").chapterId)

		mvc.get("/api/chapters/umd/adoption") { with(signedIn("gus@hack4impact.org")) }.andExpect { status { isForbidden() } }
		mvc.get("/api/chapters/umd/adoption") { with(signedIn("vic@hack4impact.org")) }.andExpect {
			status { isOk() }
			jsonPath("$.canEdit") { value(false) }
		}
	}

	@Test
	fun `a resource gone from the tool is flagged, and a failing tool doesn't stop the others`() {
		scanner.scan(setOf("umd"))
		slack.resources = slack.resources.filterKeys { it.externalId != "C-food" }
		github.failure = Unavailable(Tool.GITHUB, "down")
		val scans = scanner.scan(setOf("umd")).associateBy { it.tool }
		assertEquals("failed", scans.getValue(Tool.GITHUB).status)
		assertEquals("completed", scans.getValue(Tool.SLACK).status)
		assertNotNull(discovered("slack", "C-food").goneAt)
		assertEquals("failed", dsl.select(DISCOVERY_SCAN.STATUS).from(DISCOVERY_SCAN).where(DISCOVERY_SCAN.ID.eq(scans.getValue(Tool.GITHUB).scanId)).fetchSingle().value1())
		assertEquals(true, resource("umd-food-bank").gone)
	}

	@Test
	fun `extra chapter prefixes match on the next scan`() {
		slack.resources = mapOf(channel("C-terps", "terps-rise-dc", "U-ada"))
		scanner.scan(setOf("umd"))
		assertEquals("unmatched", discovered("slack", "C-terps").state)
		scanner.setPrefixes("umd", setOf("Terps"))
		scanner.scan(setOf("umd"))
		assertEquals(rise, discovered("slack", "C-terps").projectId)
		assertEquals("2027-05-31", reports.report(umd, true).grandfatheredUntil.toString()) // the default: end of May
		scanner.setGrandfatherUntil("umd", java.time.LocalDate.parse("2026-12-20"))
		assertEquals("2026-12-20", reports.report(umd, true).grandfatheredUntil.toString())
	}

	@Test
	fun `addresses outside hack4impact org are partly hidden in the report`() {
		assertEquals("a…@gmail.com", AdoptionReports.mask("ada.lovelace@gmail.com"))
		assertEquals("ada@umd.hack4impact.org", AdoptionReports.mask("ada@umd.hack4impact.org"))
		assertEquals("ada-gh", AdoptionReports.mask("ada-gh"))
	}
}
