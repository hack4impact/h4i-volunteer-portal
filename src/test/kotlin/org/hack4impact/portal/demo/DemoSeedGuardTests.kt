package org.hack4impact.portal.demo

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Second review L2: which databases the demo seed (which can empty every table) may write to. */
class DemoSeedGuardTests {
	@Test
	fun `a local database may be seeded`() {
		assertNull(DemoSeed.refusal("localhost", false))
		assertNull(DemoSeed.refusal("127.0.0.1", false))
	}

	@Test
	fun `the compose host db only when the run says it's staging's`() {
		assertNotNull(DemoSeed.refusal("db", false))
		assertNull(DemoSeed.refusal("db", true))
	}

	@Test
	fun `any other host never, even with the staging setting`() {
		assertNotNull(DemoSeed.refusal("portal-prod.internal", true))
		assertNotNull(DemoSeed.refusal(null, true))
	}
}
