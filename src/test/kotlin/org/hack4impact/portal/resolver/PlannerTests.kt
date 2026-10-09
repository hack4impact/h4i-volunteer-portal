package org.hack4impact.portal.resolver

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlannerTests {
	private val ada = UUID(0, 1)
	private val channel = UUID(0, 2)
	private val repo = UUID(0, 3)
	private val reason = listOf(Reason.ChapterMember(UUID(0, 9)))

	private fun desire(vararg grants: Grant) = Resolution(grants.toList(), emptyList())

	@Test
	fun `missing access is added`() {
		val plan = Planner.plan(desire(Grant(ada, channel, Access.WRITE, reason)), emptyList(), emptyMap())
		assertEquals(1, plan.adds.size)
		assertFalse(plan.isEmpty)
	}

	@Test
	fun `different access is changed`() {
		val plan = Planner.plan(desire(Grant(ada, channel, Access.ADMIN, reason)), listOf(Membership(ada, channel, Access.WRITE)), emptyMap())
		assertEquals(listOf(AccessChange(Grant(ada, channel, Access.ADMIN, reason), Access.WRITE)), plan.changes)
		assertFalse(plan.isEmpty)
	}

	@Test
	fun `access the portal granted earlier is removed, with the reasons it had`() {
		val plan = Planner.plan(desire(), listOf(Membership(ada, repo, Access.WRITE)), mapOf((ada to repo) to reason))
		assertEquals(listOf(Removal(ada, repo, Access.WRITE, reason)), plan.removals)
		assertFalse(plan.isEmpty)
	}

	@Test
	fun `access the portal never granted is drift, never removed`() {
		val plan = Planner.plan(desire(), listOf(Membership(ada, repo, Access.WRITE)), mapOf((ada to repo) to emptyList()))
		assertEquals(listOf(Membership(ada, repo, Access.WRITE)), plan.drift)
		assertTrue(plan.isEmpty)
	}

	@Test
	fun `matching access needs nothing`() {
		val plan = Planner.plan(desire(Grant(ada, channel, Access.WRITE, reason)), listOf(Membership(ada, channel, Access.WRITE)), emptyMap())
		assertTrue(plan.isEmpty)
	}
}
