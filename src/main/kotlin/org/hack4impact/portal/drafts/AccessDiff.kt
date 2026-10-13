package org.hack4impact.portal.drafts

import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.projects.ProjectQueries
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.resolver.WithheldReason
import org.hack4impact.portal.sync.WorldLoader
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What a change does to access: resolve before and after (inside a transaction that's rolled back) and compare,
 * person by person. Drafts preview with it (step 12), and so do rule edits (step 14).
 */
@Component
class AccessDiff(private val dsl: DSLContext, private val worlds: WorldLoader) {
	companion object {
		private val TOOL_LABEL = mapOf("slack" to "Slack", "github" to "GitHub", "google" to "Google group", "notion" to "Notion", "vaultwarden" to "Vaultwarden")

		/** "umd-rise-dc (Slack)": the screens turn this back into a chip with the tool's icon. */
		fun label(tool: String, name: String) = "$name (${TOOL_LABEL[tool] ?: tool})"
	}

	/** Who gets what, who waits on an agreement, and who's on which project: before or after the draft. */
	data class Snapshot(val grants: Map<Pair<UUID, UUID>, Access>, val awaiting: Set<Pair<UUID, UUID>>, val members: Set<Pair<UUID, UUID>>)

	fun snapshot(): Snapshot {
		val resolution = Resolver.resolve(worlds.load().world)
		val members = dsl.select(PROJECT_MEMBER.PERSON_ID, PROJECT_MEMBER.PROJECT_ID).from(PROJECT_MEMBER).join(PROJECT).on(PROJECT.ID.eq(PROJECT_MEMBER.PROJECT_ID))
			.where(PROJECT_MEMBER.REMOVED_AT.isNull, PROJECT.STATUS.ne("closed")).fetch().map { it.value1()!! to it.value2()!! }.toSet()
		return Snapshot(
			resolution.grants.associate { (it.personId to it.resourceId) to it.access },
			resolution.withheld.filter { it.why is WithheldReason.AwaitingAgreement }.map { it.personId to it.resourceId }.toSet(),
			members,
		)
	}

	fun diff(before: Snapshot, after: Snapshot): DraftPreview {
		val added = after.grants.keys - before.grants.keys
		val removed = before.grants.keys - after.grants.keys
		val changed = after.grants.keys.intersect(before.grants.keys).filter { after.grants[it] != before.grants[it] }
		val waiting = after.awaiting - before.awaiting
		val joins = after.members - before.members
		val leaves = before.members - after.members
		val people = (added + removed + changed + waiting + joins + leaves).map { it.first }.toSet()
		val resourceIds = (added + removed + changed + waiting).map { it.second }.toSet()
		val resources = if (resourceIds.isEmpty()) emptyMap() else
			dsl.select(RESOURCE.ID, RESOURCE.TOOL, RESOURCE.NAME).from(RESOURCE).where(RESOURCE.ID.`in`(resourceIds)).fetch().associate { it.value1()!! to label(it.value2()!!, it.value3()!!) }
		val projectIds = (joins + leaves).map { it.second }.toSet()
		val projectNames = if (projectIds.isEmpty()) emptyMap() else dsl.select(PROJECT.ID, PROJECT.NAME).from(PROJECT).where(PROJECT.ID.`in`(projectIds)).fetch().associate { it.value1()!! to it.value2()!! }
		val names = if (people.isEmpty()) emptyMap() else dsl.select(PERSON.ID, PERSON.FIRST_NAME, PERSON.LAST_NAME, PERSON.PREFERRED_NAME).from(PERSON).where(PERSON.ID.`in`(people)).fetch()
			.associate { it.value1()!! to ProjectQueries.name(it.value4(), it.value2()!!, it.value3()!!) }
		fun named(keys: Collection<Pair<UUID, UUID>>, person: UUID, labels: Map<UUID, String>) = keys.filter { it.first == person }.mapNotNull { labels[it.second] }.sorted()
		val perPerson = people.map { p ->
			PersonAccessChange(
				p, names[p] ?: "someone", named(joins, p, projectNames), named(leaves, p, projectNames),
				named(added, p, resources) + changed.filter { it.first == p }.mapNotNull { k -> resources[k.second]?.let { "$it, now ${after.grants[k]!!.name.lowercase()}" } },
				named(removed, p, resources), named(waiting, p, resources),
			)
		}.sortedBy { it.name }
		return DraftPreview(added.size, removed.size, changed.size, people.size, resourceIds.size, waiting.size, perPerson)
	}
}
