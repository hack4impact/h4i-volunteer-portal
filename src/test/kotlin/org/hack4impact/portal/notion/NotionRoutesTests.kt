package org.hack4impact.portal.notion

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NotionRoutesTests {
	private val routes = listOf(Route("tag", "lts", "page-lts"), Route("type", "new-build", "page-projects"), Route("tag", "client", "page-client"))

	@Test
	fun `the first matching route wins, by tag or type`() {
		assertEquals(PagePlacement("page-lts", "tag lts"), NotionRoutes.place(listOf("client", "LTS"), "new-build", routes, "page-default"))
		assertEquals(PagePlacement("page-projects", "type new-build"), NotionRoutes.place(listOf("client"), "New-Build", routes, null))
	}

	@Test
	fun `no match goes to the default, and without one nowhere`() {
		assertEquals(PagePlacement("page-default", "default"), NotionRoutes.place(listOf("eng"), null, routes, "page-default"))
		assertEquals(PagePlacement(null, null), NotionRoutes.place(emptyList(), null, routes, null))
	}

	@Test
	fun `titles fill the pattern and drop an empty semester`() {
		assertEquals("RISE DC (Fall 2026)", NotionRoutes.title("{project} ({semester})", "RISE DC", "Fall 2026", "umd"))
		assertEquals("RISE DC", NotionRoutes.title("{project} ({semester})", "RISE DC", null, "umd"))
		assertEquals("umd · RISE DC", NotionRoutes.title("{chapter} · {project}", "RISE DC", null, "umd"))
	}
}
