package org.hack4impact.portal.registration

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import jakarta.servlet.http.Cookie
import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.google.GoogleAccounts
import org.hack4impact.portal.db.tables.references.ADMIN_TASK
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_QUESTION
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.CLAIM_LINK
import org.hack4impact.portal.db.tables.references.INSTITUTION
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sent emails, kept for the test to read codes and links from. */
class CapturedMail : MailSender {
	data class Sent(val to: String, val subject: String, val text: String)

	val sent = mutableListOf<Sent>()
	override fun send(to: String, subject: String, text: String) { sent += Sent(to, subject, text) }
	fun last(to: String) = sent.last { it.to == to }
}

@TestConfiguration(proxyBeanMethods = false)
class RegistrationFakes {
	companion object {
		/** Fake GitHub (OAuth and API) and fake Google Directory, one WireMock. */
		val server = WireMockServer(options().dynamicPort()).apply { start() }
	}

	@Bean
	@Primary
	fun capturedMail() = CapturedMail()

	@Bean
	fun googleAccounts() = GoogleAccounts(server.baseUrl(), { "ya29.test" })
}

/** Build plan step 10: registration, approval, claims and roster invites, through the API, with Google and GitHub faked. */
@Import(TestcontainersConfiguration::class, RegistrationFakes::class)
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val mail: CapturedMail,
	@Autowired private val limiter: RateLimiter,
	@Autowired private val deadline: ClaimDeadline,
) {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun github(registry: DynamicPropertyRegistry) {
			registry.add("portal.github-oauth.client-id") { "client" }
			registry.add("portal.github-oauth.client-secret") { "secret" }
			registry.add("portal.github-oauth.web-url") { RegistrationFakes.server.baseUrl() }
			registry.add("portal.github-oauth.api-url") { RegistrationFakes.server.baseUrl() }
			// No background syncs after approvals: one still running when the next test empties the tables deadlocks it.
			registry.add("portal.sync.on-request") { "false" }
		}
	}

	private val server = RegistrationFakes.server
	private lateinit var umd: UUID

	private fun signedIn(email: String) = oidcLogin().idToken {
		it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	private fun body(result: ResultActionsDsl): JsonNode = HttpJson.MAPPER.readTree(result.andReturn().response.contentAsString)

	private fun json(path: String, body: String, cookie: Cookie? = null, as_: String? = null, method: String = "POST"): ResultActionsDsl {
		val setup: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {
			with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body
			cookie?.let { cookie(it) }
			as_?.let { with(signedIn(it)) }
		}
		return if (method == "PUT") mvc.put(path) { setup() } else mvc.post(path) { setup() }
	}

	private fun me(cookie: Cookie) = mvc.get("/api/apply/me") { cookie(cookie) }

	/** School email → code → session cookie. */
	private fun verified(email: String): Cookie {
		json("/api/apply/chapters/umd/code", """{"email":"$email"}""").andExpect { status { isAccepted() } }
		val code = Regex("\\d{6}").find(mail.last(email).subject)!!.value
		return json("/api/apply/chapters/umd/verify", """{"email":"$email","code":"$code"}""").andExpect { status { isOk() } }.andReturn().response.getCookie("h4i_applicant")!!
	}

	/** GitHub sign-in through the fake: start (state) → callback. */
	private fun linkGitHub(cookie: Cookie, id: Long, login: String) {
		server.stubFor(post(urlEqualTo("/login/oauth/access_token")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"access_token":"gho_$login"}""")))
		server.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":$id,"login":"$login"}""")))
		val redirect = mvc.get("/api/apply/github/start") { cookie(cookie) }.andExpect { status { is3xxRedirection() } }.andReturn().response.redirectedUrl!!
		val state = Regex("state=([^&]+)").find(redirect)!!.groupValues[1]
		mvc.get("/api/apply/github/callback?code=abc&state=$state") { cookie(cookie) }.andExpect { redirectedUrl("/apply") }
	}

	private fun person(first: String, status: String, school: String? = null, org: String? = null, imported: Boolean = false): UUID =
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, status).set(PERSON.SCHOOL_EMAIL, school)
			.set(PERSON.ORG_EMAIL, org).set(PERSON.SOURCE_ID, if (imported) UUID.randomUUID() else null)
			.returningResult(PERSON.ID).fetchSingle().value1()!!
			.also { dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, it).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute() }

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		mail.sent.clear()
		limiter.reset()
		server.resetAll()
		// Google: no address is taken yet; account creation answers with an ID.
		server.stubFor(get(urlPathMatching("/admin/directory/v1/users/.*")).willReturn(aResponse().withStatus(404).withBody("{}")))
		server.stubFor(post(urlEqualTo("/admin/directory/v1/users")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"g-new"}""")))
		val school = dsl.insertInto(INSTITUTION).set(INSTITUTION.NAME, "University of Maryland").set(INSTITUTION.EMAIL_DOMAINS, arrayOf("umd.edu"))
			.returningResult(INSTITUTION.ID).fetchSingle().value1()!!
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "Hack4Impact UMD").set(CHAPTER.INSTITUTION_ID, school)
			.set(CHAPTER.GOOGLE_ORG_UNIT, "/UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		person("Lena", "active", org = "lena@hack4impact.org").also { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, it).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "lead").execute() }
		person("Vic", "active", org = "vic@hack4impact.org").also { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, it).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "viewer").execute() }
		dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, "Nora").set(PERSON.LAST_NAME, "National").set(PERSON.STATUS, "active").set(PERSON.ORG_EMAIL, "nora@hack4impact.org")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		json("/api/chapters/umd/registration-settings", """{"questions":[{"label":"Why do you want to join?","kind":"long_text","required":true}]}""", as_ = "lena@hack4impact.org", method = "PUT")
			.andExpect { status { isOk() } }
	}

	@Test
	fun `a new student goes from the link to an approved member with a Google account`() {
		mvc.get("/api/apply/chapters/umd").andExpect { jsonPath("$.questions[0].label") { value("Why do you want to join?") } }
		val cookie = verified("ada@umd.edu")
		me(cookie).andExpect {
			jsonPath("$.emailVerified") { value(true) }
			jsonPath("$.missing") { value(org.hamcrest.Matchers.hasItems("your GitHub account", "your name", "“Why do you want to join?”")) }
		}
		linkGitHub(cookie, 777, "ada-gh")
		val question = body(mvc.get("/api/apply/chapters/umd")).path("questions")[0].path("id").asString()
		json("/api/apply/me", """{"details":{"firstName":"Ada","lastName":"Lovelace","personalEmail":"ada@personal.test","graduationYear":2028},"answers":{"$question":"To build things"}}""",
			cookie, method = "PUT").andExpect { jsonPath("$.missing.length()") { value(0) } }
		json("/api/apply/me/submit", "{}", cookie).andExpect { jsonPath("$.status") { value("submitted") } }

		val pending = body(mvc.get("/api/chapters/umd/registrations") { with(signedIn("lena@hack4impact.org")) })[0]
		assertEquals("new", pending.path("matchStatus").asString())
		assertEquals("To build things", pending.path("answers").path("Why do you want to join?").asString())
		json("/api/chapters/umd/registrations/${pending.path("id").asString()}/approve", "{}", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } }
		json("/api/chapters/umd/registrations/${pending.path("id").asString()}/approve", "{}", as_ = "lena@hack4impact.org").andExpect { status { isOk() } }

		val ada = dsl.selectFrom(PERSON).where(PERSON.FIRST_NAME.eq("Ada")).fetchSingle()
		assertEquals("active", ada.status)
		assertEquals("ada@umd.hack4impact.org", ada.orgEmail) // first@<chapter>.hack4impact.org (decision 108)
		assertTrue(dsl.fetchExists(CHAPTER_MEMBERSHIP, CHAPTER_MEMBERSHIP.PERSON_ID.eq(ada.id), CHAPTER_MEMBERSHIP.CHAPTER_ID.eq(umd)))
		assertEquals("777", dsl.select(TOOL_ACCOUNT.EXTERNAL_ID).from(TOOL_ACCOUNT).where(TOOL_ACCOUNT.PERSON_ID.eq(ada.id), TOOL_ACCOUNT.TOOL.eq("github")).fetchSingle().value1())
		val created = HttpJson.MAPPER.readTree(server.allServeEvents.single { it.request.method.name == "POST" && it.request.url == "/admin/directory/v1/users" }.request.bodyAsString)
		assertEquals("/UMD", created.path("orgUnitPath").asString())
		assertTrue(created.path("changePasswordAtNextLogin").asBoolean())
		val welcome = mail.last("ada@personal.test")
		assertTrue("ada@umd.hack4impact.org" in welcome.text && "Temporary password" in welcome.text)
		me(cookie).andExpect { status { isUnauthorized() } } // the applicant session ended (PRD)
	}

	@Test
	fun `codes go only to school addresses, expire after a few wrong tries, and are rate limited`() {
		json("/api/apply/chapters/umd/code", """{"email":"ada@gmail.com"}""").andExpect { status { isBadRequest() } }
		json("/api/apply/chapters/umd/code", """{"email":"ada@umd.edu"}""").andExpect { status { isAccepted() } }
		repeat(RegistrationService.CODE_ATTEMPTS) { json("/api/apply/chapters/umd/verify", """{"email":"ada@umd.edu","code":"000000"}""").andExpect { status { isBadRequest() } } }
		val code = Regex("\\d{6}").find(mail.last("ada@umd.edu").subject)!!.value
		json("/api/apply/chapters/umd/verify", """{"email":"ada@umd.edu","code":"$code"}""").andExpect { status { isBadRequest() } } // too many wrong ones: ask again
		repeat(4) { json("/api/apply/chapters/umd/code", """{"email":"ada@umd.edu"}""").andExpect { status { isAccepted() } } }
		json("/api/apply/chapters/umd/code", """{"email":"ada@umd.edu"}""").andExpect { status { isTooManyRequests() } }
	}

	@Test
	fun `an applicant session reaches only its own registration, and the applicant API needs CSRF`() {
		val cookie = verified("ada@umd.edu")
		mvc.get("/api/me") { cookie(cookie) }.andExpect { status { isUnauthorized() } } // not a Google sign-in
		mvc.get("/api/chapters/umd/members") { cookie(cookie) }.andExpect { status { isUnauthorized() } }
		mvc.get("/api/apply/me").andExpect { status { isUnauthorized() } }
		mvc.post("/api/apply/me/submit") { cookie(cookie) }.andExpect { status { isForbidden() } } // no CSRF token
		mvc.get("/api/apply/github/callback?code=abc&state=forged") { cookie(cookie) }.andExpect { redirectedUrl("/apply?github=failed") }
	}

	@Test
	fun `matches say returning, alumni or possible duplicate, and approving a match updates that record`() {
		val old = person("Ada", "alumni", school = "ada@umd.edu")
		val cookie = verified("ada@umd.edu")
		linkGitHub(cookie, 777, "ada-gh")
		val question = body(mvc.get("/api/apply/chapters/umd")).path("questions")[0].path("id").asString()
		json("/api/apply/me", """{"details":{"firstName":"Ada","lastName":"Test","graduationYear":2028},"answers":{"$question":"Back again"}}""", cookie, method = "PUT")
		json("/api/apply/me/submit", "{}", cookie)
		val reg = body(mvc.get("/api/chapters/umd/registrations") { with(signedIn("lena@hack4impact.org")) })[0]
		assertEquals("alumni", reg.path("matchStatus").asString()) // "alumni record found"
		assertEquals(old.toString(), reg.path("matches")[0].path("personId").asString())
		json("/api/chapters/umd/registrations/${reg.path("id").asString()}/approve", """{"personId":"${UUID.randomUUID()}"}""", as_ = "lena@hack4impact.org").andExpect { status { isBadRequest() } }
		json("/api/chapters/umd/registrations/${reg.path("id").asString()}/approve", """{"personId":"$old"}""", as_ = "lena@hack4impact.org").andExpect { status { isOk() } }
		assertEquals(1, dsl.fetchCount(PERSON, PERSON.FIRST_NAME.eq("Ada"))) // no new person
		assertEquals("active", dsl.select(PERSON.STATUS).from(PERSON).where(PERSON.ID.eq(old)).fetchSingle().value1())
	}

	@Test
	fun `a decline needs a reason and emails it`() {
		val cookie = verified("ada@umd.edu")
		linkGitHub(cookie, 777, "ada-gh")
		val question = body(mvc.get("/api/apply/chapters/umd")).path("questions")[0].path("id").asString()
		json("/api/apply/me", """{"details":{"firstName":"Ada","lastName":"Lovelace","graduationYear":2028},"answers":{"$question":"x"}}""", cookie, method = "PUT")
		json("/api/apply/me/submit", "{}", cookie)
		val id = body(mvc.get("/api/chapters/umd/registrations") { with(signedIn("lena@hack4impact.org")) })[0].path("id").asString()
		json("/api/chapters/umd/registrations/$id/decline", """{"reason":" "}""", as_ = "lena@hack4impact.org").andExpect { status { isBadRequest() } }
		json("/api/chapters/umd/registrations/$id/decline", """{"reason":"We're full this semester"}""", as_ = "lena@hack4impact.org").andExpect { status { isOk() } }
		assertTrue("We're full this semester" in mail.last("ada@umd.edu").text)
		assertEquals("declined", dsl.select(REGISTRATION.STATUS).from(REGISTRATION).fetchSingle().value1())
	}

	@Test
	fun `an imported member claims their record with a single-use link, without approval`() {
		val ada = person("Ada", "active", school = "ada@umd.edu", imported = true)
		dsl.update(PERSON).set(PERSON.PERSONAL_EMAIL, "ada@personal.test").where(PERSON.ID.eq(ada)).execute()
		dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, ada).set(TOOL_ACCOUNT.TOOL, "github").set(TOOL_ACCOUNT.EXTERNAL_LOGIN, "old-login").execute()
		mvc.get("/api/national/claims/preview?chapter=umd") { with(signedIn("lena@hack4impact.org")) }.andExpect { status { isForbidden() } }
		mvc.get("/api/national/claims/preview?chapter=umd") { with(signedIn("nora@hack4impact.org")) }.andExpect { jsonPath("$.eligible") { value(1) } }
		json("/api/national/claims/send", """{"chapter":"umd"}""", as_ = "nora@hack4impact.org").andExpect { jsonPath("$.alreadySent") { value(1) } }
		assertEquals(setOf("ada@umd.edu", "ada@personal.test"), mail.sent.filter { it.subject.startsWith("Claim") }.map { it.to }.toSet()) // every address on file
		val token = Regex("/claim/([A-Za-z0-9_-]+)").find(mail.last("ada@umd.edu").text)!!.groupValues[1]

		val cookie = json("/api/apply/claim", """{"token":"$token"}""").andExpect {
			status { isOk() }
			jsonPath("$.kind") { value("claim") }
			jsonPath("$.details.firstName") { value("Ada") } // pre-filled
		}.andReturn().response.getCookie("h4i_applicant")!!
		json("/api/apply/claim", """{"token":"$token"}""").andExpect { status { isGone() } } // single use
		linkGitHub(cookie, 999, "new-login")
		json("/api/apply/me", """{"details":{"firstName":"Ada","lastName":"Test","stillActive":false}}""", cookie, method = "PUT")
		json("/api/apply/me/submit", "{}", cookie).andExpect { status { isOk() } }

		val p = dsl.selectFrom(PERSON).where(PERSON.ID.eq(ada)).fetchSingle()
		assertEquals("alumni", p.status) // said they've left
		assertNotNull(p.claimedAt)
		assertEquals("ada@umd.hack4impact.org", p.orgEmail)
		val reg = dsl.selectFrom(REGISTRATION).fetchSingle()
		assertEquals("completed", reg.status)
		assertTrue(reg.githubChanged!!) // a different GitHub account than on record: flagged
		assertEquals("999", dsl.select(TOOL_ACCOUNT.EXTERNAL_ID).from(TOOL_ACCOUNT).where(TOOL_ACCOUNT.PERSON_ID.eq(ada), TOOL_ACCOUNT.TOOL.eq("github")).fetchSingle().value1())
	}

	@Test
	fun `a member of an inactive chapter can still open their claim (review H5)`() {
		person("Ada", "active", school = "ada@umd.edu", imported = true)
		dsl.update(org.hack4impact.portal.db.tables.references.CHAPTER).set(org.hack4impact.portal.db.tables.references.CHAPTER.STATUS, "inactive").execute()
		json("/api/national/claims/send", """{"chapter":"umd"}""", as_ = "nora@hack4impact.org")
		val token = Regex("/claim/([A-Za-z0-9_-]+)").find(mail.last("ada@umd.edu").text)!!.groupValues[1]
		json("/api/apply/claim", """{"token":"$token"}""").andExpect { status { isOk() }; jsonPath("$.kind") { value("claim") }; jsonPath("$.chapter.code") { value("umd") } }
		mvc.get("/api/apply/chapters/umd").andExpect { status { isNotFound() } } // nobody new applies to it
	}

	@Test
	fun `unclaimed records become alumni a month after their first link`() {
		val ada = person("Ada", "active", school = "ada@umd.edu", imported = true)
		json("/api/national/claims/send", """{"chapter":"umd"}""", as_ = "nora@hack4impact.org")
		assertEquals(0, deadline.run())
		dsl.update(CLAIM_LINK).set(CLAIM_LINK.CREATED_AT, OffsetDateTime.now().minusDays(31)).execute()
		assertEquals(1, deadline.run())
		assertEquals("alumni", dsl.select(PERSON.STATUS).from(PERSON).where(PERSON.ID.eq(ada)).fetchSingle().value1())
	}

	@Test
	fun `a roster invite is pre-filled and approved on submit, unless the match is unclear`() {
		json("/api/chapters/umd/roster", """{"csv":"first_name,last_name,school_email\nGrace,Hopper,grace@umd.edu\nbad row,,\n"}""", as_ = "lena@hack4impact.org").andExpect {
			jsonPath("$.invited") { value(1) }
			jsonPath("$.skipped.length()") { value(1) }
		}
		val token = Regex("invite=([A-Za-z0-9_-]+)").find(mail.last("grace@umd.edu").text)!!.groupValues[1]
		val cookie = json("/api/apply/roster", """{"token":"$token"}""").andExpect { jsonPath("$.details.lastName") { value("Hopper") } }
			.andReturn().response.getCookie("h4i_applicant")!!
		linkGitHub(cookie, 555, "grace-gh")
		val question = body(mvc.get("/api/apply/chapters/umd")).path("questions")[0].path("id").asString()
		json("/api/apply/me", """{"details":{"firstName":"Grace","lastName":"Hopper","graduationYear":2027},"answers":{"$question":"Roster"}}""", cookie, method = "PUT")
		json("/api/apply/me/submit", "{}", cookie)
		val reg = dsl.selectFrom(REGISTRATION).fetchSingle()
		assertEquals("approved", reg.status)
		assertNull(reg.decidedBy) // nobody had to approve
		assertEquals("active", dsl.select(PERSON.STATUS).from(PERSON).where(PERSON.FIRST_NAME.eq("Grace")).fetchSingle().value1())
	}

	@Test
	fun `without the chapter's org unit, approval still works and national gets a task to create the account`() {
		dsl.update(CHAPTER).setNull(CHAPTER.GOOGLE_ORG_UNIT).execute()
		val cookie = verified("ada@umd.edu")
		linkGitHub(cookie, 777, "ada-gh")
		val question = body(mvc.get("/api/apply/chapters/umd")).path("questions")[0].path("id").asString()
		json("/api/apply/me", """{"details":{"firstName":"Ada","lastName":"Lovelace","graduationYear":2028},"answers":{"$question":"x"}}""", cookie, method = "PUT")
		json("/api/apply/me/submit", "{}", cookie)
		val id = body(mvc.get("/api/chapters/umd/registrations") { with(signedIn("lena@hack4impact.org")) })[0].path("id").asString()
		json("/api/chapters/umd/registrations/$id/approve", "{}", as_ = "lena@hack4impact.org").andExpect { status { isOk() } }
		assertEquals("create_account", dsl.select(ADMIN_TASK.ACTION).from(ADMIN_TASK).fetchSingle().value1())
	}

	@Test
	fun `a chapter running its own application process turns the portal's off, and roster invites still work`() {
		val started = verified("ada@umd.edu") // began before the switch
		linkGitHub(started, 777, "ada-gh")
		val question = body(mvc.get("/api/apply/chapters/umd")).path("questions")[0].path("id").asString()
		json("/api/apply/me", """{"details":{"firstName":"Ada","lastName":"Lovelace","graduationYear":2028},"answers":{"$question":"x"}}""", started, method = "PUT")

		val closed = """{"questions":[{"label":"Why do you want to join?","kind":"long_text","required":true}],"applicationsOpen":false,"applicationUrl":"%s"}"""
		json("/api/chapters/umd/registration-settings", closed.format("https://forms.gle/umd"), as_ = "vic@hack4impact.org", method = "PUT").andExpect { status { isForbidden() } }
		json("/api/chapters/umd/registration-settings", closed.format("javascript:alert(1)"), as_ = "lena@hack4impact.org", method = "PUT").andExpect { status { isBadRequest() } }
		json("/api/chapters/umd/registration-settings", closed.format("https://forms.gle/umd"), as_ = "lena@hack4impact.org", method = "PUT")
			.andExpect { jsonPath("$.applicationsOpen") { value(false) } }

		mvc.get("/api/apply/chapters/umd").andExpect {
			jsonPath("$.applicationsOpen") { value(false) }
			jsonPath("$.applicationUrl") { value("https://forms.gle/umd") }
		}
		json("/api/apply/chapters/umd/code", """{"email":"bo@umd.edu"}""").andExpect { status { isForbidden() } }
		json("/api/apply/me/submit", "{}", started).andExpect { status { isForbidden() } }
		assertEquals("started", dsl.select(REGISTRATION.STATUS).from(REGISTRATION).fetchSingle().value1())

		json("/api/chapters/umd/roster", """{"csv":"first_name,last_name,school_email\nGrace,Hopper,grace@umd.edu"}""", as_ = "lena@hack4impact.org")
			.andExpect { jsonPath("$.invited") { value(1) } }
		val token = Regex("invite=([A-Za-z0-9_-]+)").find(mail.last("grace@umd.edu").text)!!.groupValues[1]
		val invited = json("/api/apply/roster", """{"token":"$token"}""").andExpect {
			status { isOk() }
			jsonPath("$.chapter.questions.length()") { value(0) } // the chapter asked its own questions already
		}.andReturn().response.getCookie("h4i_applicant")!!
		linkGitHub(invited, 555, "grace-gh")
		json("/api/apply/me", """{"details":{"firstName":"Grace","lastName":"Hopper","graduationYear":2027}}""", invited, method = "PUT")
			.andExpect { jsonPath("$.missing.length()") { value(0) } }
		assertEquals(1, dsl.fetchCount(CHAPTER_QUESTION)) // kept for if the chapter switches back
	}

	@Test
	fun `only national sets where accounts go, leads set the questions`() {
		json("/api/chapters/umd/registration-settings", """{"questions":[],"googleDomain":"evil.example","googleOrgUnit":"/"}""", as_ = "lena@hack4impact.org", method = "PUT")
			.andExpect { jsonPath("$.googleDomain") { value("umd.hack4impact.org") } } // ignored for leads
		json("/api/chapters/umd/registration-settings", """{"questions":[],"googleDomain":"terps.hack4impact.org","googleOrgUnit":"/UMD/Members"}""", as_ = "nora@hack4impact.org", method = "PUT")
			.andExpect {
				jsonPath("$.googleDomain") { value("terps.hack4impact.org") }
				jsonPath("$.googleOrgUnit") { value("/UMD/Members") }
			}
		json("/api/chapters/umd/registration-settings", """{"questions":[{"label":"Pick","kind":"choice","options":["a"]}]}""", as_ = "lena@hack4impact.org", method = "PUT")
			.andExpect { status { isBadRequest() } }
	}
}
