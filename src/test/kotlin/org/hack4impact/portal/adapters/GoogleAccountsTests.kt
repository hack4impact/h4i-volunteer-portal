package org.hack4impact.portal.adapters

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.google.GoogleAccounts
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoogleAccountsTests {
	private val server = WireMockServer(options().dynamicPort())
	private val users = "/admin/directory/v1/users"
	private val accounts by lazy { GoogleAccounts(server.baseUrl(), { "ya29" }, orgUnitScope = "/Sandbox") }

	@BeforeAll fun start() = server.start()
	@AfterAll fun stop() = server.stop()
	@BeforeEach fun reset() = server.resetAll()

	private fun exists(email: String, ou: String = "/Sandbox") = server.stubFor(get(urlEqualTo("$users/${email.replace("@", "%40")}"))
		.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"x","primaryEmail":"$email","orgUnitPath":"$ou"}""")))

	@Test
	fun `addresses are the first name without accents, with a number when taken`() {
		assertEquals("zoeann", GoogleAccounts.localPart("Zoë-Ann"))
		assertEquals("member", GoogleAccounts.localPart("李"))
		server.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("$users/.*")).atPriority(9).willReturn(aResponse().withStatus(404).withBody("{}")))
		exists("ada@umd.hack4impact.org"); exists("ada2@umd.hack4impact.org")
		server.stubFor(post(urlEqualTo(users)).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"g1"}""")))
		val account = accounts.create("Ada", "Lovelace", "UMD.hack4impact.org", "/Sandbox/E2E", "ada@personal.test")
		assertEquals("ada3@umd.hack4impact.org", account.email)
		assertEquals(16, account.temporaryPassword.length)
	}

	@Test
	fun `accounts are only created in, and deleted from, org units inside the scope`() {
		assertFailsWith<Rejected> { accounts.create("Ada", "L", "hack4impact.org", "/UMD", null) }
		assertFailsWith<Rejected> { accounts.create("Ada", "L", "hack4impact.org", "/SandboxNot", null) } // a path boundary, not a prefix
		exists("real@hack4impact.org", ou = "/UMD")
		assertFailsWith<Rejected> { accounts.delete("real@hack4impact.org") }
		exists("e2e@hack4impact.org", ou = "/Sandbox/E2E")
		server.stubFor(delete(urlEqualTo("$users/e2e%40hack4impact.org")).willReturn(aResponse().withStatus(204)))
		accounts.delete("e2e@hack4impact.org")
		server.verify(1, deleteRequestedFor(urlEqualTo("$users/e2e%40hack4impact.org")))
	}

	@Test
	fun `org units without a leading slash are fixed, since Google rejects them`() {
		assertEquals("/Sandbox/E2E", GoogleAccounts.orgUnitPath(" Sandbox/E2E/ "))
		val loose = GoogleAccounts(server.baseUrl(), { "ya29" }, orgUnitScope = "Sandbox")
		server.stubFor(get(com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching("$users/.*")).willReturn(aResponse().withStatus(404).withBody("{}")))
		server.stubFor(post(urlEqualTo(users)).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"g1"}""")))
		loose.create("Ada", "L", "hack4impact.org", "Sandbox/E2E", null)
		server.verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo(users))
			.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.orgUnitPath", com.github.tomakehurst.wiremock.client.WireMock.equalTo("/Sandbox/E2E"))))
		assertFailsWith<Rejected> { loose.create("Ada", "L", "hack4impact.org", "UMD", null) } // the scope still holds
	}
}
