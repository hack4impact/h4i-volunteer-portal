package org.hack4impact.portal.tools

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps adding a tool a matter of its adapters and one descriptor (wiki: tool plugins). A test can't add an enum
 * value, so instead of a fake tool these check the two ways that could stop being true.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ToolPluginGuardTests(
	@Autowired private val mvc: MockMvc,
	@Autowired private val registry: ToolRegistry,
	@Autowired private val dsl: org.jooq.DSLContext,
	@Autowired private val worlds: org.hack4impact.portal.sync.WorldLoader,
) {
	companion object {
		private val KEYS = Tool.entries.map { it.name.lowercase() }

		/** Files that may name several tools: the registry, the adapters' wiring and icons, and data that is one tool each. */
		private val ALLOWED = setOf(
			"src/main/kotlin/org/hack4impact/portal/tools/ToolRegistry.kt",
			"src/main/kotlin/org/hack4impact/portal/adapters/AdapterConfiguration.kt", // one block per tool's adapters
			"src/main/kotlin/org/hack4impact/portal/sync/SandboxE2E.kt", // the live suite checks each tool its own way
			"src/main/kotlin/org/hack4impact/portal/sync/SandboxCheck.kt", // and so does the live read-only check
			"src/main/kotlin/org/hack4impact/portal/demo/DemoSeed.kt", // demo data
			"src/main/kotlin/org/hack4impact/portal/memberimport/MemberDbImporter.kt", // the member DB's provider names
			"web/src/components/ToolIcon.tsx", // each tool's mark
			"web/src/api/schema.ts",
			"web/src/test/tools.json", // the live /api/tools, for the web tests
		)
	}

	@Test
	fun `no file outside the registry lists tools (a new list would miss a plugin)`() {
		val kotlin = File("src/main/kotlin").walk().filter { it.extension == "kt" }
		val web = File("web/src").walk().filter { (it.extension == "ts" || it.extension == "tsx") && !it.path.contains("test") }
		val listing = (kotlin + web).mapNotNull { file ->
			val path = file.invariantSeparatorsPath
			if (path in ALLOWED) return@mapNotNull null
			val text = file.readText()
			val named = KEYS.filter { key -> Regex("\"$key\"|'$key'|\\bTool\\.${key.uppercase()}\\b|\\b$key:").containsMatchIn(text) }
			"$path names ${named.joinToString()}".takeIf { named.size >= 3 }
		}.toList()
		assertTrue(listing.isEmpty(), "Read these from the tool registry instead (wiki: tool plugins):\n" + listing.joinToString("\n"))
	}

	@Test
	fun `a resource of a tool no code knows is left out of the sync, not the end of it (tool plugins review)`() {
		val r = org.hack4impact.portal.db.tables.references.RESOURCE
		val id = dsl.insertInto(r).set(r.TOOL, "figma").set(r.NAME, "left-behind").returningResult(r.ID).fetchSingle().value1()!!
		try {
			val loaded = worlds.load()
			assertTrue(loaded.world.resources.none { it.id == id })
			assertTrue(loaded.warnings.any { "unknown tool 'figma'" in it }, loaded.warnings.toString())
		} finally {
			dsl.deleteFrom(r).where(r.ID.eq(id)).execute()
		}
	}

	@Test
	fun `the web app gets every tool from the registry`() {
		val signedIn = oidcLogin().idToken { it.subject("sub-x").claim("email", "x@hack4impact.org").claim("email_verified", true).claim("hd", "hack4impact.org") }
		mvc.get("/api/tools") { with(signedIn) }.andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(registry.all.size) }
			jsonPath("$[0].key") { value("slack") }
			jsonPath("$[0].namePrefix") { value("#") }
		}
		assertEquals(Tool.entries.size, registry.all.size)
	}

	@Test
	fun `the web tests' tool list is the live one (rewritten with -PupdateApiSpec)`() {
		val signedIn = oidcLogin().idToken { it.subject("sub-x").claim("email", "x@hack4impact.org").claim("email_verified", true).claim("hd", "hack4impact.org") }
		val live = mvc.get("/api/tools") { with(signedIn) }.andReturn().response.contentAsString
		val pretty = org.hack4impact.portal.adapters.HttpJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(org.hack4impact.portal.adapters.HttpJson.MAPPER.readTree(live)) + "\n"
		val fixture = File("web/src/test/tools.json")
		if (System.getProperty("updateApiSpec") == "true") fixture.writeText(pretty)
		assertEquals(pretty, fixture.readText(), "web/src/test/tools.json is out of date. Run: ./gradlew test --tests '*ToolPluginGuardTests' -PupdateApiSpec")
	}
}
