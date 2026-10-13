package org.hack4impact.portal.projects

import org.hack4impact.portal.TestcontainersConfiguration
import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.SYNC_RUN
import org.hack4impact.portal.emptyPortalTables
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A project's activity feed: edits merged, what the sync did, agreements, and paging back. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ProjectActivityTests(
	@Autowired private val dsl: DSLContext,
	@Autowired private val projects: ProjectService,
	@Autowired private val activity: ProjectActivity,
) {
	companion object {
		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			registry.add("portal.agreements.on-request") { "false" }
		}
	}

	@Test
	fun `edits by one person merge, the sync reads per person or grouped, and older activity pages back`() {
		dsl.emptyPortalTables()
		val umd = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "umd").set(CHAPTER.NAME, "UMD").returningResult(CHAPTER.ID).fetchSingle().value1()!!
		fun person(first: String) = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, "Test").set(PERSON.STATUS, "active")
			.returningResult(PERSON.ID).fetchSingle().value1()!!.also { dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, it).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, umd).execute() }
		val lena = person("Lena")
		val lead = Viewer(lena, "lena@hack4impact.org", "Lena Test", false, mapOf(umd to "lead"))
		val project = projects.create(umd, ProjectInput("RISE DC"), lead)
		fun record() = dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(project.id)).fetchSingle()
		val people = listOf("Ada", "Bo", "Cy", "Di", "Ed").map(::person)
		people.take(2).forEach { projects.addMember(umd, record(), MemberInput(it), lead) }

		// One sync run that added Ada to the channel and invited her to the team; a later, bigger one.
		val slack = dsl.select(RESOURCE.ID).from(RESOURCE).join(PROJECT_RESOURCE).on(PROJECT_RESOURCE.RESOURCE_ID.eq(RESOURCE.ID))
			.where(PROJECT_RESOURCE.PROJECT_ID.eq(project.id), RESOURCE.TOOL.eq("slack")).fetchSingle().value1()
		val github = dsl.select(RESOURCE.ID).from(RESOURCE).join(PROJECT_RESOURCE).on(PROJECT_RESOURCE.RESOURCE_ID.eq(RESOURCE.ID))
			.where(PROJECT_RESOURCE.PROJECT_ID.eq(project.id), RESOURCE.TOOL.eq("github")).fetchSingle().value1()
		fun run(at: OffsetDateTime, changes: List<Triple<UUID, UUID?, String>>) {
			val id = dsl.insertInto(SYNC_RUN).set(SYNC_RUN.MODE, "apply").set(SYNC_RUN.TRIGGER, "manual").set(SYNC_RUN.STATUS, "completed").set(SYNC_RUN.STARTED_AT, at)
				.returningResult(SYNC_RUN.ID).fetchSingle().value1()
			changes.forEach { (who, resource, outcome) ->
				dsl.insertInto(SYNC_CHANGE).set(SYNC_CHANGE.RUN_ID, id).set(SYNC_CHANGE.KIND, "add").set(SYNC_CHANGE.PERSON_ID, who).set(SYNC_CHANGE.RESOURCE_ID, resource)
					.set(SYNC_CHANGE.OUTCOME, outcome).execute()
			}
		}
		run(OffsetDateTime.now().plusMinutes(1), listOf(Triple(people[0], slack, "applied"), Triple(people[0], github, "invited")))
		run(OffsetDateTime.now().plusMinutes(2), people.map { Triple(it, slack, "applied") })
		val template = dsl.insertInto(AGREEMENT_TEMPLATE).set(AGREEMENT_TEMPLATE.KIND, "project").set(AGREEMENT_TEMPLATE.NAME, "Project agreement")
			.set(AGREEMENT_TEMPLATE.DOCUMENSO_TEMPLATE_ID, "t").set(AGREEMENT_TEMPLATE.VERSION, 1).returningResult(AGREEMENT_TEMPLATE.ID).fetchSingle().value1()
		dsl.insertInto(AGREEMENT).set(AGREEMENT.PERSON_ID, people[1]).set(AGREEMENT.PROJECT_ID, project.id).set(AGREEMENT.TEMPLATE_ID, template)
			.set(AGREEMENT.STATUS, "signed").set(AGREEMENT.SIGNED_AT, OffsetDateTime.now().plusMinutes(3)).execute()

		val feed = activity.page(project.id!!, null, 10).items
		assertEquals(
			listOf("Bo Test signed the project agreement", "Updated access for 5 people", "Ada Test added to #umd-rise-dc and invited to umd-rise-dc", "Added Ada Test and Bo Test"),
			feed.take(4).map { it.text },
		)
		assertEquals(5, feed[1].details.size) // the big run, one line per person
		assertEquals("Lena Test", feed[3].actor)
		assertTrue(feed.last().text.startsWith("Created RISE DC"))

		// Paging: two at a time, then what's older than the last one shown.
		val first = activity.page(project.id!!, null, 2)
		assertTrue(first.more)
		val next = activity.page(project.id!!, first.items.last().at, 10)
		assertEquals("Ada Test added to #umd-rise-dc and invited to umd-rise-dc", next.items.first().text)
		assertTrue(!next.more)
		assertTrue(dsl.fetchCount(AUDIT_EVENT) > 0)
	}
}
