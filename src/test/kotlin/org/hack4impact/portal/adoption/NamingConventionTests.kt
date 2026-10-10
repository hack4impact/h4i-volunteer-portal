package org.hack4impact.portal.adoption

import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NamingConventionTests {
	private val umd = UUID.randomUUID()
	private val uiuc = UUID.randomUUID()
	private val u = UUID.randomUUID()
	private val rise = UUID.randomUUID()
	private val riseDcV2 = UUID.randomUUID()
	private val chapters = listOf(ChapterNames(umd, "umd", setOf("terps")), ChapterNames(uiuc, "uiuc"), ChapterNames(u, "u"))
	private val projects = listOf(ProjectNames(rise, umd, "rise-dc"), ProjectNames(riseDcV2, umd, "rise-dc-v2"), ProjectNames(UUID.randomUUID(), uiuc, "rise-dc"))

	private fun match(tool: Tool, externalId: String, name: String = externalId) = NamingConvention.match(tool, externalId, name, chapters, projects)

	@Test
	fun `the PRD's examples match their chapter and project in each tool`() {
		val expected = ConventionMatch(umd, Target.PROJECT_TEAM, rise)
		assertEquals(expected, match(Tool.SLACK, "C123", "umd-rise-dc"))
		assertEquals(expected, match(Tool.GITHUB, "umd-rise-dc", "UMD RISE DC"))
		assertEquals(expected, match(Tool.GOOGLE, "umd-rise-dc@hack4impact.org", "Anything"))
	}

	@Test
	fun `a chapter's own name is its members, and -leads its leads`() {
		assertEquals(ConventionMatch(umd, Target.CHAPTER_MEMBERS), match(Tool.SLACK, "C1", "umd"))
		assertEquals(ConventionMatch(umd, Target.CHAPTER_LEADS), match(Tool.GOOGLE, "umd-leads@hack4impact.org"))
	}

	@Test
	fun `a suffix after the project slug still matches the project, and the longest slug wins`() {
		assertEquals(rise, match(Tool.SLACK, "C1", "umd-rise-dc-client")?.projectId)
		assertEquals(riseDcV2, match(Tool.SLACK, "C1", "umd-rise-dc-v2")?.projectId)
	}

	@Test
	fun `projects only match within their chapter`() {
		assertEquals(ConventionMatch(uiuc, Target.PROJECT_TEAM, projects[2].id), match(Tool.SLACK, "C1", "uiuc-rise-dc"))
	}

	@Test
	fun `an unknown project leaves the chapter matched with a suggested slug and no target`() {
		assertEquals(ConventionMatch(umd, null, suggestedSlug = "food-bank"), match(Tool.SLACK, "C1", "umd-food-bank"))
	}

	@Test
	fun `extra prefixes work like the code, and the longest prefix wins`() {
		assertEquals(ConventionMatch(umd, Target.PROJECT_TEAM, rise), match(Tool.SLACK, "C1", "terps-rise-dc"))
		assertEquals(uiuc, match(Tool.SLACK, "C1", "uiuc-general")?.chapterId) // not chapter "u"
		assertEquals(ConventionMatch(u, null, suggestedSlug = "general"), match(Tool.SLACK, "C1", "u-general"))
	}

	@Test
	fun `case, hashes, spaces and underscores don't matter, but the dash boundary does`() {
		assertEquals(rise, match(Tool.SLACK, "C1", "#UMD_Rise DC")?.projectId)
		assertNull(match(Tool.SLACK, "C1", "umdrisedc"))
		assertNull(match(Tool.SLACK, "C1", "general"))
		assertNull(match(Tool.GOOGLE, "umd-rise-dc-team@other.org".replace("umd", "x")))
	}

	@Test
	fun `Google matches on the address, not the display name`() {
		assertNull(match(Tool.GOOGLE, "board@hack4impact.org", "umd-rise-dc"))
	}
}
