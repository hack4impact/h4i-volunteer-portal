package org.hack4impact.portal.notion

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.NOTION_ROUTE
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.emptyPortalTables
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.sync.SyncEngine
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A Notion API on WireMock, as the app's Notion client. */
@TestConfiguration(proxyBeanMethods = false)
class FakeNotion {
	companion object {
		val server = WireMockServer(options().dynamicPort()).apply { start() }
	}

	@Bean
	fun notionClient() = NotionClient(server.baseUrl(), "secret")
}

/** Step 9: project pages are created under the chapter's route when Notion is out of dry run, and trashed on close. */
@Import(TestcontainersConfiguration::class, FakeNotion::class)
@SpringBootTest(properties = ["portal.adapters.notion.write=true"])
class NotionPagesTests(@Autowired private val dsl: DSLContext, @Autowired private val engine: SyncEngine) {
	private val server = FakeNotion.server
	private val parent = "22222222-2222-2222-2222-222222222222"

	@BeforeEach
	fun seed() {
		dsl.emptyPortalTables()
		server.resetAll()
		server.stubFor(post(urlEqualTo("/pages")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"page-1"}""")))
		server.stubFor(patch(urlEqualTo("/pages/page-1")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"id":"page-1"}""")))
		val umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").set(CHAPTER.NOTION_TITLE_PATTERN, "{project} ({semester})")
			.returningResult(CHAPTER.ID).fetchSingle().value1()!!
		dsl.insertInto(NOTION_ROUTE).set(NOTION_ROUTE.CHAPTER_ID, umd).set(NOTION_ROUTE.POSITION, 1).set(NOTION_ROUTE.MATCH_KIND, "tag")
			.set(NOTION_ROUTE.MATCH_VALUE, "lts").set(NOTION_ROUTE.PARENT_PAGE_ID, parent).execute()
		dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "RISE DC").set(PROJECT.SLUG, "rise-dc").set(PROJECT.STATUS, "active")
			.set(PROJECT.TAGS, arrayOf("lts")).execute()
		dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, umd).set(PROJECT.NAME, "No Route").set(PROJECT.SLUG, "no-route").set(PROJECT.STATUS, "active").execute()
	}

	@Test
	fun `pages appear only out of dry run, once, under the matching route, and go to the trash on close`() {
		engine.run("manual", setOf(Tool.NOTION))
		assertEquals(0, server.allServeEvents.size) // Notion is in dry run

		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.eq("notion")).execute()
		engine.run("manual", setOf(Tool.NOTION))
		engine.run("manual", setOf(Tool.NOTION))
		server.verify(1, postRequestedFor(urlEqualTo("/pages"))) // once, and not for the project without a route
		val body = org.hack4impact.portal.adapters.HttpJson.MAPPER.readTree(server.allServeEvents.single { it.request.url == "/pages" }.request.bodyAsString)
		assertEquals(parent, body.path("parent").path("page_id").asString())
		assertEquals("RISE DC", body.path("properties").path("title").path("title")[0].path("text").path("content").asString())
		assertEquals("page-1", dsl.select(PROJECT.NOTION_PAGE_ID).from(PROJECT).where(PROJECT.SLUG.eq("rise-dc")).fetchSingle().value1())

		dsl.update(PROJECT).set(PROJECT.STATUS, "closed").where(PROJECT.SLUG.eq("rise-dc")).execute()
		engine.run("manual", setOf(Tool.NOTION))
		engine.run("manual", setOf(Tool.NOTION))
		server.verify(1, patchRequestedFor(urlEqualTo("/pages/page-1")))
		assertNotNull(dsl.select(PROJECT.NOTION_PAGE_ARCHIVED_AT).from(PROJECT).where(PROJECT.SLUG.eq("rise-dc")).fetchSingle().value1())
	}
}
