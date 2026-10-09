package org.hack4impact.portal.resolver

import java.util.UUID

/** One person's actual access to one resource, as read from a tool by an adapter. */
data class Membership(val personId: UUID, val resourceId: UUID, val access: Access)

data class AccessChange(val grant: Grant, val from: Access)

/** Access the portal granted earlier and no longer wants, with the reasons it was granted for. */
data class Removal(val personId: UUID, val resourceId: UUID, val access: Access, val previousReasons: List<Reason>)

/**
 * What to change in the tools so they match the resolver. [drift] is access the portal never granted
 * (made by hand in a tool); the plan reports it but never removes it. Adoption (step 7) decides about it.
 */
data class Plan(
	val adds: List<Grant>,
	val changes: List<AccessChange>,
	val removals: List<Removal>,
	val drift: List<Membership>,
) {
	val isEmpty get() = adds.isEmpty() && changes.isEmpty() && removals.isEmpty()
}

object Planner {

	/**
	 * Diffs the desired grants against what the tools actually have. [previous] holds, per (person, resource),
	 * the reasons the portal last granted it for (the sync records). Only pairs with previous reasons can be
	 * removed, so the portal never takes away access it didn't give.
	 */
	fun plan(desired: Resolution, actual: Collection<Membership>, previous: Map<Pair<UUID, UUID>, List<Reason>>): Plan {
		val have = actual.associateBy { it.personId to it.resourceId }
		val want = desired.grants.associateBy { it.personId to it.resourceId }
		val adds = desired.grants.filter { (it.personId to it.resourceId) !in have }
		val changes = desired.grants.mapNotNull { grant ->
			have[grant.personId to grant.resourceId]?.takeIf { it.access != grant.access }?.let { AccessChange(grant, it.access) }
		}
		val (removable, drift) = have.filterKeys { it !in want }.values.partition { !previous[it.personId to it.resourceId].isNullOrEmpty() }
		val order = compareBy<Membership>({ it.personId }, { it.resourceId })
		return Plan(
			adds = adds,
			changes = changes,
			removals = removable.sortedWith(order).map { Removal(it.personId, it.resourceId, it.access, previous.getValue(it.personId to it.resourceId)) },
			drift = drift.sortedWith(order),
		)
	}
}
