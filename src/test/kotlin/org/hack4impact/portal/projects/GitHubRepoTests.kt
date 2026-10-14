package org.hack4impact.portal.projects

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.InMemoryReadAdapter
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.Unavailable
import org.hack4impact.portal.adapters.github.GitHubRepo
import org.hack4impact.portal.adapters.github.GitHubRepoApi
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.GITHUB_REPO
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.RESOURCE
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GitHub as far as project repositories go: the org's repositories and each team's access to them. */
class FakeRepos : GitHubRepoApi {
	val repos = mutableMapOf<String, GitHubRepo>()
	val access = mutableMapOf<String, MutableMap<String, String>>()
	val calls = mutableListOf<String>()
	var fail: AdapterException? = null
	private var next = 100L

	fun reset() { repos.clear(); access.clear(); calls.clear(); fail = null }

	override fun list() = repos.values.toList()
	override fun find(name: String) = repos[name.lowercase()]
	override fun createEmpty(name: String, private: Boolean) = make("create $name private=$private", name, private)
	override fun generate(template: String, name: String, private: Boolean) = make("generate $name from $template private=$private", name, private)

	private fun make(call: String, name: String, private: Boolean): GitHubRepo {
		fail?.let { throw it }
		if (name.lowercase() in repos) throw Rejected(Tool.GITHUB, "name already exists on this account", 422)
		calls += call
		return GitHubRepo(next++, name, false, private).also { repos[name.lowercase()] = it }
	}

	override fun teamAccess(team: String) = access[team].orEmpty().toMap()
	override fun setTeamAccess(team: String, repo: String, permission: String) {
		calls += "access $team $repo $permission"
		access.getOrPut(team) { mutableMapOf() }[repo.lowercase()] = permission
	}
	override fun removeTeamAccess(team: String, repo: String) {
		calls += "remove $team $repo"
		access[team]?.remove(repo.lowercase())
	}
}

@TestConfiguration(proxyBeanMethods = false)
class RepoFakes {
	@Bean
	fun fakeRepos() = FakeRepos()
}

/** Project GitHub repositories (wiki: github-repos, decision 147): leads add them, the sync makes them and keeps the team's access. */
@Import(TestcontainersConfiguration::class, FakeTools::class, RepoFakes::class)
@SpringBootTest(properties = ["portal.sync.on-request=false", "portal.agreements.on-request=false", "portal.adapters.github.org=h4i-test"])
@AutoConfigureMockMvc
class GitHubRepoTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val dsl: DSLContext,
	@Autowired private val engine: SyncEngine,
	@Autowired private val github: InMemoryReadAdapter,
	@Autowired private val fake: FakeRepos,
) {
	private val json = JsonMapper.builder().build()
	private lateinit var umd: UUID
	private lateinit var team: UUID

	private fun signedIn(email: String) = oidcLogin().idToken {
		it.subject("sub-$email").claim("email", email).claim("email_verified", true).claim("hd", "hack4impact.org")
	}

	private fun post(path: String, body: String, as_: String = "lena@hack4impact.org"): ResultActionsDsl =
		mvc.post(path) { with(signedIn(as_)); with(csrf()); contentType = MediaType.APPLICATION_JSON; content = body }

	private fun body(result: ResultActionsDsl): JsonNode = json.readTree(result.andReturn().response.contentAsString)

	private fun project(): JsonNode = body(mvc.get("/api/chapters/umd/projects/rise-dc") { with(signedIn("lena@hack4impact.org")) })

	private fun repos(p: JsonNode = project()) = p.path("resources").first { it.path("resourceId").asString() == team.toString() }.path("repos")

	private fun add(body: String, as_: String = "lena@hack4impact.org") = post("/api/chapters/umd/projects/rise-dc/resources/$team/repos", body, as_)

	private fun state(name: String) = dsl.select(GITHUB_REPO.STATE).from(GITHUB_REPO).where(GITHUB_REPO.NAME.eq(name)).fetchOne()?.value1()

	private fun githubRun() = engine.run("manual", setOf(Tool.GITHUB))

	private fun notes() = dsl.select(AUDIT_EVENT.AFTER).from(AUDIT_EVENT).where(AUDIT_EVENT.ACTION.eq("project.repo_sync")).fetch().map { it.value1()!!.data() }

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		fake.reset()
		github.failure = null; github.accounts = emptyList(); github.resources = mapOf(ToolResource("umd-rise-dc", "umd-rise-dc") to emptyList())
		umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		fun person(first: String, email: String) = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active")
			.set(PERSON.ORG_EMAIL, email).returningResult(PERSON.ID).fetchSingle().value1()!!
			.also { dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, it).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute() }
		person("Lena", "lena@hack4impact.org").also { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, it).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "lead").execute() }
		person("Vic", "vic@hack4impact.org").also { dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, it).set(CHAPTER_ROLE.CHAPTER_ID, umd).set(CHAPTER_ROLE.ROLE, "viewer").execute() }
		person("Nora", "nora@hack4impact.org").also { dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, it).execute() }
		val created = body(post("/api/chapters/umd/projects", """{"name":"RISE DC"}""").andExpect { status { isCreated() } })
		team = UUID.fromString(created.path("resources").first { it.path("tool").asString() == "github" }.path("resourceId").asString())
		post("/api/chapters/umd/projects/rise-dc/status", """{"status":"active"}""").andExpect { status { isOk() } }
		// The team exists in GitHub (the team step made it), and GitHub is out of dry run.
		dsl.update(RESOURCE).set(RESOURCE.EXTERNAL_ID, "umd-rise-dc").where(RESOURCE.ID.eq(team)).execute()
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.eq("github")).execute()
	}

	@Test
	fun `a lead adds repositories to a project's GitHub team, with GitHub's rules and the team's level as the default`() {
		val p = body(add("""{"name":"umd-rise-dc"}""").andExpect { status { isOk() } })
		with(repos(p).single()) {
			assertEquals("umd-rise-dc", path("name").asString())
			assertEquals("empty", path("source").asString())
			assertEquals(false, path("private").asBoolean()) // public by default (Q44)
			assertEquals("push", path("permission").asString()) // the team's write level on the project
			assertEquals("pending", path("state").asString())
			assertTrue(path("url").isNull) // not in GitHub yet
		}
		assertTrue(project().path("resources").filter { it.path("tool").asString() != "github" }.all { it.path("repos").isNull })
		assertTrue(project().path("activity").any { it.path("text").asString() == "Added the repository umd-rise-dc to umd-rise-dc" })

		add("""{"name":"has space"}""").andExpect { status { isBadRequest() } }
		add("""{"name":"umd-x.git"}""").andExpect { status { isBadRequest() } }
		add("""{"name":"umd-x","source":"template"}""").andExpect { status { isBadRequest() } } // which template?
		add("""{"name":"umd-x","permission":"admin"}""").andExpect { status { isBadRequest() } }
		add("""{"name":"UMD-RISE-DC"}""").andExpect { status { isConflict() } } // one team per repository
		add("""{"name":"umd-y"}""", as_ = "vic@hack4impact.org").andExpect { status { isForbidden() } } // viewers can't
		val slack = project().path("resources").first { it.path("tool").asString() == "slack" }.path("resourceId").asString()
		post("/api/chapters/umd/projects/rise-dc/resources/$slack/repos", """{"name":"umd-z"}""").andExpect { status { isBadRequest() } }

		// Linking: a lead only their chapter's repositories; national any.
		add("""{"name":"old-site","source":"linked"}""").andExpect { status { isForbidden() } }
		add("""{"name":"umd-old-site","source":"linked","private":true}""").andExpect { status { isOk() } }
		assertEquals(false, dsl.select(GITHUB_REPO.PRIVATE).from(GITHUB_REPO).where(GITHUB_REPO.NAME.eq("umd-old-site")).fetchSingle().value1()) // linking never changes it
		add("""{"name":"old-site","source":"linked","permission":"pull"}""", as_ = "nora@hack4impact.org").andExpect { status { isOk() } }
	}

	@Test
	fun `the sync creates each repository once, gives the team access, and puts that access back after a hand change`() {
		fake.repos["starter"] = GitHubRepo(1, "starter", true, false)
		add("""{"name":"umd-rise-dc","private":true}""")
		add("""{"name":"umd-rise-dc-site","source":"template","template":"starter","permission":"maintain"}""")
		fake.repos["umd-old"] = GitHubRepo(2, "umd-old", false, false)
		add("""{"name":"umd-old","source":"linked"}""")

		githubRun()
		assertEquals(
			listOf(
				"create umd-rise-dc private=true", "access umd-rise-dc umd-rise-dc push",
				"generate umd-rise-dc-site from starter private=false", "access umd-rise-dc umd-rise-dc-site maintain",
				"access umd-rise-dc umd-old push", // linked: only the team's access
			),
			fake.calls,
		)
		assertTrue(repos().all { it.path("state").asString() == "ready" && it.path("url").asString().startsWith("https://github.com/h4i-test/") }, repos().toString())
		assertNotNull(dsl.select(GITHUB_REPO.GITHUB_ID).from(GITHUB_REPO).where(GITHUB_REPO.NAME.eq("umd-rise-dc")).fetchSingle().value1())
		assertTrue(notes().any { "Created the repository umd-rise-dc on GitHub and gave umd-rise-dc write access" in it }, notes().toString())
		assertTrue(notes().any { "Linked the repository umd-old" in it })

		fake.calls.clear()
		githubRun()
		assertEquals(emptyList(), fake.calls) // nothing to do

		fake.access["umd-rise-dc"]!!["umd-rise-dc"] = "pull" // someone lowered it in GitHub
		fake.access["umd-rise-dc"]!!.remove("umd-old") // and removed this one
		githubRun()
		assertEquals(setOf("access umd-rise-dc umd-rise-dc push", "access umd-rise-dc umd-old push"), fake.calls.toSet())
		assertTrue(notes().any { "Gave umd-rise-dc write access to umd-rise-dc again (it had read access, changed in GitHub)" in it }, notes().toString())
		assertTrue(notes().any { "access to umd-old again (it had none" in it })
	}

	@Test
	fun `forgetting a repository takes the team's access away and leaves the repository, and one never made just goes`() {
		add("""{"name":"umd-rise-dc"}""")
		githubRun()
		val id = repos().single().path("id").asString()
		mvc.delete("/api/chapters/umd/projects/rise-dc/repos/$id") { with(signedIn("lena@hack4impact.org")); with(csrf()) }.andExpect { status { isOk() } }
		assertEquals("forgetting", state("umd-rise-dc"))
		fake.calls.clear()
		githubRun()
		assertEquals(listOf("remove umd-rise-dc umd-rise-dc"), fake.calls)
		assertNull(state("umd-rise-dc"))
		assertTrue("umd-rise-dc" in fake.repos) // still in GitHub (Q45)

		add("""{"name":"umd-never"}""")
		val never = repos().single().path("id").asString()
		mvc.delete("/api/chapters/umd/projects/rise-dc/repos/$never") { with(signedIn("lena@hack4impact.org")); with(csrf()) }.andExpect { status { isOk() } }
		assertNull(state("umd-never"))
	}

	@Test
	fun `nothing happens in dry run, a failure backs off, and a taken name waits for the lead to try again`() {
		add("""{"name":"umd-rise-dc"}""")
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, true).where(TOOL_SETTING.TOOL.eq("github")).execute()
		githubRun()
		engine.dryRun("manual")
		assertEquals(emptyList(), fake.calls)
		assertEquals("pending", state("umd-rise-dc"))
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.eq("github")).execute()

		fake.fail = Unavailable(Tool.GITHUB, "down")
		githubRun()
		with(dsl.selectFrom(GITHUB_REPO).fetchSingle()) {
			assertEquals("pending", state)
			assertEquals(1, attempts)
			assertTrue(retryAt!!.isAfter(OffsetDateTime.now()))
			assertTrue("down" in lastError!!)
		}
		fake.fail = null
		githubRun()
		assertEquals(emptyList(), fake.calls) // not due yet
		dsl.update(GITHUB_REPO).set(GITHUB_REPO.RETRY_AT, OffsetDateTime.now().minusMinutes(1)).execute()
		githubRun()
		assertEquals("ready", state("umd-rise-dc"))

		fake.repos["umd-taken"] = GitHubRepo(9, "umd-taken", false, false)
		add("""{"name":"umd-taken"}""")
		githubRun()
		assertEquals("failed", state("umd-taken")) // refused at once: retrying won't help
		val failed = repos().first { it.path("name").asString() == "umd-taken" }
		assertTrue("link it instead" in failed.path("error").asString() || "already exists" in failed.path("error").asString(), failed.toString())
		post("/api/chapters/umd/projects/rise-dc/repos/${failed.path("id").asString()}/retry", "{}").andExpect { status { isOk() } }
		assertEquals("pending", state("umd-taken"))
		post("/api/chapters/umd/projects/rise-dc/repos/${failed.path("id").asString()}/retry", "{}").andExpect { status { isConflict() } } // not failed now
	}

	@Test
	fun `in a draft a repository is a draft change, made when the draft is released`() {
		val draft = body(post("/api/chapters/umd/drafts", """{"name":"Spring"}""").andExpect { status { isCreated() } }).path("id").asString()
		post("/api/chapters/umd/projects/rise-dc/resources/$team/repos?draft=$draft", """{"name":"umd-rise-dc"}""").andExpect { status { isOk() } }
		assertNull(state("umd-rise-dc")) // not live yet
		val shown = body(mvc.get("/api/chapters/umd/projects/rise-dc?draft=$draft") { with(signedIn("lena@hack4impact.org")) })
		assertEquals("umd-rise-dc", repos(shown).single().path("name").asString())
		post("/api/chapters/umd/drafts/$draft/release", "{}").andExpect { status { isOk() }; jsonPath("$.released") { value(true) } }
		assertEquals("pending", state("umd-rise-dc"))
	}

	@Test
	fun `the dialog lists the org's templates, and the repositories a lead may link`() {
		fake.repos["starter"] = GitHubRepo(1, "starter", true, false)
		fake.repos["umd-old"] = GitHubRepo(2, "umd-old", false, false)
		fake.repos["gt-thing"] = GitHubRepo(3, "gt-thing", false, false)
		mvc.get("/api/chapters/umd/github-repos") { with(signedIn("lena@hack4impact.org")) }.andExpect {
			jsonPath("$.available") { value(true) }
			jsonPath("$.templates[0]") { value("starter") }
			jsonPath("$.repos.length()") { value(1) } // only umd-…
			jsonPath("$.repos[0].name") { value("umd-old") }
			jsonPath("$.linkAny") { value(false) }
		}
		mvc.get("/api/chapters/umd/github-repos") { with(signedIn("nora@hack4impact.org")) }.andExpect { jsonPath("$.repos.length()") { value(3) } }
		mvc.get("/api/chapters/umd/github-repos") { with(signedIn("vic@hack4impact.org")) }.andExpect { status { isForbidden() } }
	}
}
