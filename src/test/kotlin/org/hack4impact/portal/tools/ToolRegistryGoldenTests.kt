package org.hack4impact.portal.tools

import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the portal said about each essential tool before the tool registry (2026-10-13, wiki: tool plugins), so
 * moving the scattered lists into the registry can't quietly change one. Each value is copied from where it lived.
 */
class ToolRegistryGoldenTests {
	private val registry = ToolRegistry(StaticListableBeanFactory().getBeanProvider(ToolDescriptor::class.java))

	@Test
	fun `a stored tool name finds its tool in any case, and an unknown one finds none`() {
		assertEquals(Tool.SLACK, Tool.fromKey("Slack"))
		assertNull(Tool.fromKey("figma"))
		assertNull(Tool.fromKey(null))
	}

	@Test
	fun `every tool has a descriptor`() {
		assertEquals(Tool.entries.toSet(), registry.all.map { it.tool }.toSet())
	}

	@Test
	fun `names, labels and box titles are as before`() {
		// Rules.TOOL_NAME and web Status.TOOL_NAME
		assertEquals(mapOf("slack" to "Slack", "github" to "GitHub", "google" to "Google", "notion" to "Notion", "vaultwarden" to "Vaultwarden", "documenso" to "Documenso"),
			registry.all.associate { it.key to it.name })
		// AccessDiff.TOOL_LABEL, read back by the web's Drafts (TOOL_BY_LABEL)
		assertEquals(mapOf("slack" to "Slack", "github" to "GitHub", "google" to "Google group", "notion" to "Notion", "vaultwarden" to "Vaultwarden"),
			registry.resourceTools.associate { it.key to it.resourceLabel })
		assertEquals("umd-rise (Google group)", registry.label("google", "umd-rise"))
		// ProjectPage GROUP: box order and titles
		assertEquals(listOf("slack" to "Slack channels", "github" to "GitHub teams", "vaultwarden" to "Vaultwarden collections", "notion" to "Notion page", "google" to "Google groups"),
			registry.resourceTools.sortedBy { it.projectOrder }.map { it.key to it.resourcesTitle })
	}

	@Test
	fun `list order is the member page's and the activity feed's`() {
		// MemberHome, ProjectActivity, Automations filter (the Overview listed Google first: now the same order)
		assertEquals(listOf("slack", "google", "github", "notion", "vaultwarden"), registry.resourceTools.map { it.key })
	}

	@Test
	fun `policy flags are as before`() {
		assertEquals(setOf(Tool.GITHUB, Tool.VAULTWARDEN), registry.sensitive) // Resolver.SENSITIVE, AgreementService
		assertEquals(setOf(Tool.SLACK), registry.all.filter { it.archiveOnClose }.map { it.tool }.toSet()) // SyncEngine.ARCHIVE_ON_CLOSE
		assertEquals(setOf(Tool.GOOGLE, Tool.GITHUB, Tool.SLACK), registry.all.filter { it.discoverable }.map { it.tool }.toSet()) // AdoptionScanner
		assertEquals(setOf("github", "google", "vaultwarden"), registry.resourceTools.filter { it.hasAdminLevel }.map { it.key }.toSet()) // Rules warning: Slack, Notion have none
		assertEquals(listOf("slack", "google", "github", "notion", "vaultwarden"), registry.resourceTools.map { it.key }) // Rules.TOOLS (as a set)
	}

	@Test
	fun `naming is as before`() {
		// ProjectService.NAME_RULES and normalizeName; "#" before Slack channels (Status.resourceName, Rules, ProjectActivity)
		val rules = registry.resourceTools.associate { it.key to it.naming?.hint }
		assertEquals(mapOf("slack" to "lowercase letters, digits, - and _, at most 80", "google" to "an address at hack4impact.org", "github" to "lowercase letters, digits and -",
			"notion" to null, "vaultwarden" to "at most 100 characters"), rules)
		assertTrue(registry.find("slack")!!.naming!!.pattern.matches("umd-rise_dc"))
		assertTrue(!registry.find("slack")!!.naming!!.pattern.matches("Umd"))
		assertTrue(registry.find("google")!!.naming!!.pattern.matches("umd-rise@umd.hack4impact.org"))
		assertTrue(!registry.find("google")!!.naming!!.pattern.matches("umd-rise@gmail.com"))
		assertTrue(registry.find("github")!!.naming!!.pattern.matches("umd-rise-dc"))
		assertTrue(registry.find("vaultwarden")!!.naming!!.pattern.matches("UMD Rise: shared"))
		assertNull(registry.find("notion")!!.naming)
		assertEquals(setOf("slack", "github", "google"), registry.all.filter { it.lowercaseNames }.map { it.key }.toSet())
		// ProjectPage's add form placeholder
		assertEquals(mapOf("slack" to "{chapter}-{project}-eng", "google" to "{chapter}-{project}-eng@hack4impact.org", "github" to "{chapter}-{project}-eng",
			"notion" to "{chapter}-{project}-eng", "vaultwarden" to "{chapter}-{project}-eng"), registry.resourceTools.associate { it.key to it.nameExample })
		assertEquals("#umd-rise", registry.displayName("slack", "umd-rise"))
		assertEquals("umd-rise", registry.displayName("github", "umd-rise"))
	}

	@Test
	fun `links into the tools are as before`() {
		val settings = AdapterProperties().let {
			it.copy(github = it.github.copy(org = "https://github.com/hack4impact"), vaultwarden = it.vaultwarden.copy(baseUrl = "https://vault.example/"))
		}
		fun link(key: String, name: String, id: String) = registry.find(key)!!.link(settings, name, id)
		assertEquals("https://slack.com/app_redirect?channel=C1", link("slack", "umd-rise", "C1"))
		assertEquals("https://github.com/orgs/hack4impact/teams/umd-rise", link("github", "umd-rise", "123"))
		assertEquals("https://groups.google.com/a/umd.hack4impact.org/g/umd-rise", link("google", "umd-rise@umd.hack4impact.org", "g1"))
		assertEquals("https://www.notion.so/abc123", link("notion", "Page", "abc-123"))
		assertEquals("https://vault.example", link("vaultwarden", "x", "v1"))
		assertNull(link("documenso", "x", "d1"))
	}
}
