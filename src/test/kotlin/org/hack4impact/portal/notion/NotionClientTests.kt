package org.hack4impact.portal.notion

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.adapters.AuthFailed
import org.hack4impact.portal.adapters.NotFound
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotionClientTests {
	private val server = WireMockServer(options().dynamicPort())
	private val client by lazy { NotionClient(server.baseUrl(), "secret") }
	private val root = "11111111-1111-1111-1111-111111111111"
	private val lts = "22222222-2222-2222-2222-222222222222"

	@BeforeAll fun start() = server.start()
	@AfterAll fun stop() = server.stop()
	@BeforeEach fun reset() = server.resetAll()

	private fun page(id: String, title: String, parent: String, titleProperty: String = "title") =
		server.stubFor(
			get(urlEqualTo("/pages/$id")).withHeader("Authorization", equalTo("Bearer secret")).withHeader("Notion-Version", equalTo("2022-06-28"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
					"""{"object":"page","id":"$id","parent":$parent,"archived":false,
					"properties":{"$titleProperty":{"id":"title","type":"title","title":[{"plain_text":"${title.substringBefore(' ')}"},{"plain_text":"${title.substringAfter(' ', "").let { if (it.isEmpty()) "" else " $it" }}"}]}}}""",
				)),
		)

	@Test
	fun `a page's path is built from its parents up to the workspace`() {
		page(root, "Hack4Impact-UMD", """{"type":"workspace","workspace":true}""")
		page(lts, "Long Term-Success", """{"type":"page_id","page_id":"$root"}""", titleProperty = "Name")
		assertEquals("Hack4Impact-UMD / Long Term-Success", client.path(lts))
		assertNull(client.page(root).parentPageId)
	}

	@Test
	fun `a page the integration can't see is not found, and a bad secret is an auth failure`() {
		server.stubFor(get(urlEqualTo("/pages/$lts")).willReturn(aResponse().withStatus(404).withBody("""{"object":"error","code":"object_not_found"}""")))
		assertFailsWith<NotFound> { client.path(lts) }
		server.stubFor(get(urlEqualTo("/pages/$root")).willReturn(aResponse().withStatus(401).withBody("""{"object":"error","code":"unauthorized"}""")))
		assertFailsWith<AuthFailed> { client.page(root) }
	}

	@Test
	fun `page IDs come from Notion links or IDs, with or without dashes`() {
		val id = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d"
		assertEquals(id, NotionClient.pageId("https://www.notion.so/hack4impact/Long-Term-Success-1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d?pvs=4"))
		assertEquals(id, NotionClient.pageId("1A2B3C4D5E6F7A8B9C0D1E2F3A4B5C6D"))
		assertEquals(id, NotionClient.pageId(id))
		assertNull(NotionClient.pageId("https://www.notion.so/hack4impact/Long-Term-Success"))
	}

	@Test
	fun `creates a page with the template's content two levels deep, skipping what can't be recreated`() {
		val template = "33333333-3333-3333-3333-333333333333"
		fun children(id: String, body: String) = server.stubFor(get(urlEqualTo("/blocks/$id/children?page_size=100"))
			.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)))
		children(template, """{"results":[
			{"id":"b1","type":"heading_1","has_children":false,"heading_1":{"rich_text":[{"type":"text","text":{"content":"Overview"}}]}},
			{"id":"b2","type":"toggle","has_children":true,"toggle":{"rich_text":[{"type":"text","text":{"content":"Links"}}]}},
			{"id":"b3","type":"child_database","has_children":false,"child_database":{"title":"Tasks"}}],"has_more":false}""")
		children("b2", """{"results":[{"id":"b4","type":"paragraph","has_children":false,"paragraph":{"rich_text":[]}}],"has_more":false}""")
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/pages"))
			.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"new-page"}""")))

		assertEquals("new-page", client.createPage(lts, "RISE DC (Fall 2026)", template))
		val sent = org.hack4impact.portal.adapters.HttpJson.MAPPER.readTree(server.allServeEvents.first { it.request.url == "/pages" }.request.bodyAsString)
		assertEquals(lts, sent.path("parent").path("page_id").asString())
		assertEquals("RISE DC (Fall 2026)", sent.path("properties").path("title").path("title")[0].path("text").path("content").asString())
		assertEquals(listOf("heading_1", "toggle"), sent.path("children").toList().map { it.path("type").asString() }) // the database is left out
		assertEquals("paragraph", sent.path("children")[1].path("toggle").path("children")[0].path("type").asString())
	}

	@Test
	fun `moves a page to the trash, and a page already gone counts as done`() {
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.patch(urlEqualTo("/pages/$lts"))
			.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"$lts","archived":true}""")))
		client.archivePage(lts)
		server.verify(com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor(urlEqualTo("/pages/$lts"))
			.withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.equalToJson("""{"archived":true}""")))
		server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.patch(urlEqualTo("/pages/$root")).willReturn(aResponse().withStatus(404).withBody("{}")))
		client.archivePage(root)
	}
}
