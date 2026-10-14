package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.put
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.github.GitHubRepo
import org.hack4impact.portal.adapters.github.GitHubRepoClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The GitHub calls behind project repositories, as GitHub's REST docs describe them *(unverified against the sandbox: wiki github-repos)*. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitHubRepoClientTests {
	private val server = WireMockServer(options().dynamicPort())
	private val org = "h4i-portal-sandbox"
	private val client by lazy { GitHubRepoClient(server.baseUrl(), org, { "ghs_test" }, pageSize = 2) }

	@BeforeAll fun start() = server.start()
	@AfterAll fun stop() = server.stop()
	@BeforeEach fun reset() = server.resetAll()

	private fun json(status: Int, body: String) = aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)

	@Test
	fun `creates an empty repository with a first commit, public or private`() {
		server.stubFor(post("/orgs/$org/repos").willReturn(json(201, """{"id":7,"name":"umd-rise-dc","private":true,"is_template":false}""")))
		assertEquals(GitHubRepo(7, "umd-rise-dc", false, true), client.createEmpty("umd-rise-dc", private = true))
		server.verify(postRequestedFor(urlEqualTo("/orgs/$org/repos")).withRequestBody(equalToJson("""{"name":"umd-rise-dc","private":true,"auto_init":true}""")))
	}

	@Test
	fun `generates a repository from a template in the org`() {
		server.stubFor(post("/repos/$org/starter/generate").willReturn(json(201, """{"id":8,"name":"umd-site","private":false}""")))
		assertEquals(8, client.generate("starter", "umd-site", private = false).id)
		server.verify(postRequestedFor(urlEqualTo("/repos/$org/starter/generate")).withRequestBody(equalToJson("""{"owner":"$org","name":"umd-site","private":false}""")))
	}

	@Test
	fun `a taken name says to link it instead`() {
		server.stubFor(post("/orgs/$org/repos").willReturn(json(422, """{"message":"Repository creation failed.","errors":[{"message":"name already exists on this account"}]}""")))
		val e = assertFailsWith<Rejected> { client.createEmpty("umd-rise-dc", private = false) }
		assertEquals(422, e.status)
		assertContains(e.message!!, "link it instead")
	}

	@Test
	fun `lists every repository across pages, with templates marked, and finds one by name`() {
		server.stubFor(get("/orgs/$org/repos?type=all&per_page=2").willReturn(
			json(200, """[{"id":1,"name":"starter","is_template":true},{"id":2,"name":"umd-a"}]""")
				.withHeader("Link", """<${server.baseUrl()}/orgs/$org/repos?type=all&per_page=2&page=2>; rel="next""""),
		))
		server.stubFor(get("/orgs/$org/repos?type=all&per_page=2&page=2").willReturn(json(200, """[{"id":3,"name":"umd-b","private":true}]""")))
		assertEquals(listOf("starter" to true, "umd-a" to false, "umd-b" to false), client.list().map { it.name to it.template })
		server.stubFor(get("/repos/$org/umd-a").willReturn(json(200, """{"id":2,"name":"umd-a"}""")))
		server.stubFor(get("/repos/$org/nope").willReturn(json(404, """{"message":"Not Found"}""")))
		assertEquals(2, client.find("umd-a")!!.id)
		assertNull(client.find("nope"))
	}

	@Test
	fun `reads, gives and takes away a team's access in GitHub's permission words`() {
		server.stubFor(get("/orgs/$org/teams/umd-rise-dc/repos?per_page=2").willReturn(json(200, """[
			{"id":1,"name":"UMD-A","role_name":"write"},
			{"id":2,"name":"umd-b","role_name":"read"},
			{"id":3,"name":"umd-c","permissions":{"admin":false,"maintain":true,"push":true,"pull":true}}
		]""")))
		assertEquals(mapOf("umd-a" to "push", "umd-b" to "pull", "umd-c" to "maintain"), client.teamAccess("umd-rise-dc"))

		server.stubFor(put("/orgs/$org/teams/umd-rise-dc/repos/$org/umd-a").willReturn(aResponse().withStatus(204)))
		client.setTeamAccess("umd-rise-dc", "umd-a", "maintain")
		server.verify(putRequestedFor(urlEqualTo("/orgs/$org/teams/umd-rise-dc/repos/$org/umd-a")).withRequestBody(equalToJson("""{"permission":"maintain"}""")))

		server.stubFor(delete("/orgs/$org/teams/umd-rise-dc/repos/$org/umd-a").willReturn(aResponse().withStatus(404)))
		client.removeTeamAccess("umd-rise-dc", "umd-a") // already gone: fine
	}
}
