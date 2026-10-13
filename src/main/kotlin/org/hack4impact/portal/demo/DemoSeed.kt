package org.hack4impact.portal.demo

import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.references.AGREEMENT
import org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_QUESTION
import org.hack4impact.portal.db.tables.references.CHAPTER_RESOURCE
import org.hack4impact.portal.rules.RuleInput
import org.hack4impact.portal.rules.RuleProjects
import org.hack4impact.portal.rules.RuleResources
import org.hack4impact.portal.rules.RuleWho
import org.hack4impact.portal.rules.Rules
import org.hack4impact.portal.db.tables.references.CHAPTER_ROLE
import org.hack4impact.portal.db.tables.references.INSTITUTION
import org.hack4impact.portal.db.tables.references.NATIONAL_ADMIN
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT_ROLE
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE_TEMPLATE
import org.hack4impact.portal.db.tables.references.ROSTER_INVITE
import org.hack4impact.portal.db.tables.references.SYNC_RECORD
import org.hack4impact.portal.db.tables.references.TERM
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.drafts.Drafts
import org.hack4impact.portal.projects.MemberInput
import org.hack4impact.portal.projects.ProjectInput
import org.hack4impact.portal.projects.ProjectService
import org.hack4impact.portal.resolver.Resolver
import org.hack4impact.portal.sync.WorldLoader
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource
import kotlin.system.exitProcess

/**
 * Demo data for staging (`portal.demo.seed=true`): a fictional chapter set up so every view can be shown. Each
 * persona signs in with a real Google test account ([Personas]); everyone else is made up and can't sign in.
 */
@ConfigurationProperties("portal.demo")
data class DemoProperties(
	val seed: Boolean = false,
	/** Empty the database first. Needed when it isn't empty; refused unless the database is local or staging's. */
	val reset: Boolean = false,
	val personas: Personas = Personas(),
) {
	/**
	 * The Google account each persona signs in with (`PORTAL_DEMO_PERSONAS_NATIONAL`, `…_LEAD`, `…_COLEAD`, `…_VIEWER`,
	 * `…_PROJECTLEAD`, `…_MEMBER`). One left blank gets a made-up address and can't sign in.
	 */
	data class Personas(
		val national: String = "",
		val lead: String = "",
		val coLead: String = "",
		val viewer: String = "",
		val projectLead: String = "",
		val member: String = "",
	)
}

/** Seeds once and exits: `--portal.demo.seed=true` (see deploy/staging/seed.sh). */
@Component
@ConditionalOnBooleanProperty("portal.demo.seed")
class DemoSeedRunner(private val seed: DemoSeed, private val context: ConfigurableApplicationContext) : ApplicationRunner {
	private val log = LoggerFactory.getLogger(javaClass)

	override fun run(args: ApplicationArguments) {
		val code = try {
			seed.seed(); 0
		} catch (e: IllegalStateException) {
			log.error("Demo seed refused: {}", e.message); 1
		}
		exitProcess(SpringApplication.exit(context, { code }))
	}
}

@Component
class DemoSeed(
	private val properties: DemoProperties,
	private val dsl: DSLContext,
	private val dataSource: DataSource,
	private val projects: ProjectService,
	private val drafts: Drafts,
	private val rules: Rules,
	private val worlds: WorldLoader,
	private val transactions: PlatformTransactionManager,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun seed() {
		val url = dataSource.connection.use { it.metaData.url }
		val host = Regex("//([^/:]+)").find(url)?.groupValues?.get(1)
		check(host in setOf("localhost", "127.0.0.1", "db")) { "only a local or staging database is seeded (this one is on $host)" }
		if (properties.reset) reset()
		check(dsl.fetchCount(PERSON) == 0) { "the database already has people; add --portal.demo.reset=true to empty it first (staging only)" }
		val plan = TransactionTemplate(transactions).execute { Data().build() }!!
		// Next semester's plan, in a draft (Drafts tab). Made after the rest is committed: drafts replay in their own,
		// rolled-back transactions.
		val (chapter, lead, team) = plan
		val draft = drafts.create(chapter, "Spring 2027 teams", lead).id
		team.forEach { (person, role) -> drafts.record(chapter, draft, lead, "shelter-intake-portal") { pr -> drafts.operation("add_member", pr!!.id!!, "input" to MemberInput(person, role)) } }
		drafts.record(chapter, draft, lead, "shelter-intake-portal") { pr -> drafts.operation("set_status", pr!!.id!!, "status" to "active") }
		val p = properties.personas
		log.info(
			"Demo data ready. Sign in as:\n  national admin  {}\n  chapter lead    {}\n  co-lead         {}\n  viewer          {}\n  project lead    {}\n  member          {}",
			p.national.ifBlank { "(not set)" }, p.lead.ifBlank { "(not set)" }, p.coLead.ifBlank { "(not set)" }, p.viewer.ifBlank { "(not set)" },
			p.projectLead.ifBlank { "(not set)" }, p.member.ifBlank { "(not set)" },
		)
	}

	private fun reset() {
		val tables = dsl.fetch("SELECT tablename FROM pg_tables WHERE schemaname = 'portal' AND tablename <> 'flyway_schema_history'").map { it.get(0, String::class.java) }
		dsl.execute("TRUNCATE " + tables.joinToString { "portal.\"$it\"" } + " CASCADE")
		dsl.execute("INSERT INTO portal.tool_setting (tool) VALUES ('google'), ('github'), ('slack'), ('notion'), ('vaultwarden'), ('documenso') ON CONFLICT DO NOTHING")
		// National's own chapter (V13) and the national project template (V5), which truncating chapters took with them.
		dsl.execute("INSERT INTO portal.chapter (code, name, kind, applications_open) VALUES ('national', 'Hack4Impact National', 'national', false) ON CONFLICT DO NOTHING")
		listOf(
			listOf("slack", "{chapter}-{project}", false, "main"), listOf("google", "{chapter}-{project}@hack4impact.org", false, "main"),
			listOf("github", "{chapter}-{project}", true, "eng"), listOf("vaultwarden", "{chapter}-{project}", true, "eng"),
		).forEachIndexed { i, (tool, pattern, gated, tag) ->
			dsl.insertInto(RESOURCE_TEMPLATE).set(RESOURCE_TEMPLATE.POSITION, i + 1).set(RESOURCE_TEMPLATE.TOOL, tool as String).set(RESOURCE_TEMPLATE.NAME_PATTERN, pattern as String)
				.set(RESOURCE_TEMPLATE.ACCESS_LEVEL, "write").set(RESOURCE_TEMPLATE.REQUIRES_AGREEMENT, gated as Boolean).set(RESOURCE_TEMPLATE.TAGS, arrayOf(tag as String)).execute()
		}
	}

	/** The demo, built inside one transaction. */
	private inner class Data {
		val people = mutableMapOf<String, UUID>()
		var counter = 0

		fun person(first: String, last: String, chapter: UUID?, status: String = "active", email: String? = null, imported: Boolean = false, github: Boolean = true): UUID {
			val key = "$first $last"
			val n = ++counter
			val id = dsl.insertInto(PERSON).set(PERSON.FIRST_NAME, first).set(PERSON.LAST_NAME, last).set(PERSON.STATUS, status)
				.set(PERSON.ORG_EMAIL, email?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "${first.lowercase()}.${last.lowercase()}@demo.example.org")
				.set(PERSON.SCHOOL_EMAIL, "${first.lowercase()}${last.first().lowercase()}@demo.edu")
				.set(PERSON.PERSONAL_EMAIL, "${first.lowercase()}.${last.lowercase()}@example.com")
				.set(PERSON.SOURCE_ID, if (imported) UUID.randomUUID() else null)
				.set(PERSON.CLAIMED_AT, if (imported) null else OffsetDateTime.now().minusDays(30L + n))
				.returningResult(PERSON.ID).fetchSingle().value1()!!
			chapter?.let { dsl.insertInto(CHAPTER_MEMBERSHIP).set(CHAPTER_MEMBERSHIP.PERSON_ID, id).set(CHAPTER_MEMBERSHIP.CHAPTER_ID, it).set(CHAPTER_MEMBERSHIP.JOINED_ON, LocalDate.now().minusMonths(6L + n % 18)).execute() }
			// Accounts in the tools, in the states a real chapter has.
			dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, id).set(TOOL_ACCOUNT.TOOL, "slack").set(TOOL_ACCOUNT.EXTERNAL_ID, "U0DEMO%04d".format(n))
				.set(TOOL_ACCOUNT.STATE, if (n % 9 == 0) "unverified" else "confirmed").execute()
			if (github) dsl.insertInto(TOOL_ACCOUNT).set(TOOL_ACCOUNT.PERSON_ID, id).set(TOOL_ACCOUNT.TOOL, "github").set(TOOL_ACCOUNT.EXTERNAL_ID, "${90000 + n}")
				.set(TOOL_ACCOUNT.EXTERNAL_LOGIN, "${first.lowercase()}-${last.lowercase()}-demo").set(TOOL_ACCOUNT.STATE, if (n % 7 == 0) "invited" else "confirmed").execute()
			people[key] = id
			return id
		}

		fun role(name: String, lead: Boolean): UUID =
			dsl.select(PROJECT_ROLE.ID).from(PROJECT_ROLE).where(PROJECT_ROLE.NAME.eq(name)).fetchOne()?.value1()
				?: dsl.insertInto(PROJECT_ROLE).set(PROJECT_ROLE.NAME, name).set(PROJECT_ROLE.IS_LEAD, lead).returningResult(PROJECT_ROLE.ID).fetchSingle().value1()!!

		/** Returns the draft to make afterwards: chapter, its lead, and the shelter project's planned team. */
		fun build(): Triple<UUID, Viewer, List<Pair<UUID, UUID>>> {
			val p = properties.personas
			val school = dsl.insertInto(INSTITUTION).set(INSTITUTION.NAME, "Demo University").set(INSTITUTION.EMAIL_DOMAINS, arrayOf("demo.edu")).returningResult(INSTITUTION.ID).fetchSingle().value1()!!
			val other = dsl.insertInto(INSTITUTION).set(INSTITUTION.NAME, "Sample State University").set(INSTITUTION.EMAIL_DOMAINS, arrayOf("samplestate.edu")).returningResult(INSTITUTION.ID).fetchSingle().value1()!!
			val demo = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "demo").set(CHAPTER.NAME, "Hack4Impact Demo University").set(CHAPTER.INSTITUTION_ID, school).set(CHAPTER.STATUS, "active")
				.returningResult(CHAPTER.ID).fetchSingle().value1()!!
			val sample = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, "sample").set(CHAPTER.NAME, "Hack4Impact Sample State").set(CHAPTER.INSTITUTION_ID, other).set(CHAPTER.STATUS, "active")
				.returningResult(CHAPTER.ID).fetchSingle().value1()!!
			val terms = listOf(Triple("spring", 2026, "01-12" to "05-08"), Triple("fall", 2026, "08-31" to "12-18"), Triple("spring", 2027, "01-19" to "05-14")).associate { (season, year, dates) ->
				"$season $year" to dsl.insertInto(TERM).set(TERM.INSTITUTION_ID, school).set(TERM.SEASON, season).set(TERM.YEAR, year.toShort())
					.set(TERM.STARTS_ON, LocalDate.parse("$year-${dates.first}")).set(TERM.ENDS_ON, LocalDate.parse("$year-${dates.second}"))
					.returningResult(TERM.ID).fetchSingle().value1()!!
			}
			val techLead = role("Tech Lead", true)
			val pm = role("Product Manager", true)
			val dev = role("Developer", false)
			val designer = role("Designer", false)

			// The personas: the people you sign in as.
			val nadia = person("Nadia", "Okafor", null, email = p.national)
			dsl.insertInto(NATIONAL_ADMIN).set(NATIONAL_ADMIN.PERSON_ID, nadia).execute()
			val leo = person("Leo", "Martinez", demo, email = p.lead)
			val chloe = person("Chloe", "Park", demo, email = p.coLead)
			val victor = person("Victor", "Hughes", demo, email = p.viewer)
			val priya = person("Priya", "Shah", demo, email = p.projectLead)
			val marcus = person("Marcus", "Chen", demo, email = p.member)
			for ((who, r, title) in listOf(Triple(leo, "lead", "Chapter director"), Triple(chloe, "co_lead", "Director of product"), Triple(victor, "viewer", "Faculty advisor"))) {
				dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, who).set(CHAPTER_ROLE.CHAPTER_ID, demo).set(CHAPTER_ROLE.ROLE, r).set(CHAPTER_ROLE.TITLE, title).execute()
			}

			// The rest of the chapter: made up.
			listOf("Ava Thompson", "Ben Carter", "Sofia Rossi", "Daniel Kim", "Emma Wilson", "Noah Patel", "Olivia Brown", "Ethan Nguyen", "Grace Lee", "Henry Adams",
				"Isabella Garcia", "Jack Turner", "Lily Evans", "Mason Wright", "Zoe Mitchell").forEach { val (f, l) = it.split(" "); person(f, l, demo) }
			listOf("Ryan Scott", "Hannah Moore", "Tyler Reed").forEach { val (f, l) = it.split(" "); person(f, l, demo, status = "alumni", github = it != "Tyler Reed") }
			person("Sam", "Rivera", demo, status = "removal_requested")
			person("Jordan", "Blake", demo, imported = true, github = false)
			fun id(name: String) = people.getValue(name)

			val leoViewer = Viewer(leo, p.lead, "Leo Martinez", false, mapOf(demo to "lead"))
			fun project(name: String, type: String, term: String, partner: String, team: List<Pair<String, UUID>>, status: String, unsigned: Set<String> = emptySet()): UUID {
				val created = projects.create(demo, ProjectInput(name, type = type, tags = listOf("client"), termId = terms.getValue(term), partner = partner), leoViewer)
				val record = { dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(created.id)).fetchSingle() }
				team.forEach { (who, r) -> projects.addMember(demo, record(), MemberInput(id(who), r), leoViewer) }
				if (status != "draft") projects.setStatus(demo, record(), "active", leoViewer)
				if (status == "paused" || status == "closed") projects.setStatus(demo, record(), status, leoViewer)
				dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.AGREEMENT_STATUS, "signed")
					.where(PROJECT_MEMBER.PROJECT_ID.eq(created.id), PROJECT_MEMBER.PERSON_ID.notIn(unsigned.map(::id))).execute()
				return created.id!!
			}
			val food = project("Food Rescue Network", "lts", "fall 2026", "City Food Bank",
				listOf("Priya Shah" to techLead, "Ava Thompson" to pm, "Marcus Chen" to dev, "Ben Carter" to dev, "Sofia Rossi" to designer, "Daniel Kim" to dev), "active",
				unsigned = setOf("Daniel Kim"))
			val tutor = project("Tutor Match", "new-build", "fall 2026", "Eastside Tutoring",
				listOf("Emma Wilson" to techLead, "Chloe Park" to pm, "Marcus Chen" to dev, "Noah Patel" to dev, "Olivia Brown" to designer, "Ryan Scott" to dev), "active",
				unsigned = setOf("Marcus Chen", "Noah Patel"))
			project("Museum Volunteer App", "lts", "spring 2026", "Riverside Museum",
				listOf("Ethan Nguyen" to techLead, "Grace Lee" to dev, "Henry Adams" to dev, "Isabella Garcia" to designer), "paused")
			project("Alumni Website", "new-build", "spring 2026", "Hack4Impact Demo University", listOf("Tyler Reed" to techLead, "Hannah Moore" to dev), "closed")
			project("Shelter Intake Portal", "new-build", "spring 2027", "Harbor Shelter", emptyList(), "draft")

			// The chapter's own channels.
			fun chapterChannel(name: String, audience: String, external: String): UUID {
				val r = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "slack").set(RESOURCE.NAME, name).set(RESOURCE.CHAPTER_ID, demo).set(RESOURCE.EXTERNAL_ID, external)
					.set(RESOURCE.MANAGED, "portal").returningResult(RESOURCE.ID).fetchSingle().value1()!!
				dsl.insertInto(CHAPTER_RESOURCE).set(CHAPTER_RESOURCE.CHAPTER_ID, demo).set(CHAPTER_RESOURCE.RESOURCE_ID, r).set(CHAPTER_RESOURCE.AUDIENCE, audience).set(CHAPTER_RESOURCE.ACCESS_LEVEL, "write").execute()
				return r
			}
			val general = chapterChannel("demo-general", "members", "C0DEMOGEN1")
			val leadsChannel = chapterChannel("demo-leads", "leads", "C0DEMOLDS1")

			// Automations (step 14): a role rule, a title rule, one turned off, and a national default with its own channel.
			rules.create(demo, RuleInput("Tech leads run the repositories", "So they can manage branch protection and reviewers",
				RuleWho(projectRoles = listOf(techLead)), RuleProjects("all"), RuleResources("tags", tags = listOf("eng")), listOf("github"), "admin"), leoViewer)
			rules.create(demo, RuleInput("Our advisor follows the leads channel", null,
				RuleWho(titles = listOf("Faculty advisor")), resources = RuleResources("ids", ids = listOf(leadsChannel))), leoViewer)
			rules.create(demo, RuleInput("Alumni stay in #demo-general", "Off until the alumni network decides",
				RuleWho(statuses = listOf("alumni"), everyone = true), resources = RuleResources("ids", ids = listOf(general)), enabled = false), leoViewer)
			dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "slack").set(RESOURCE.NAME, "h4i-chapter-leads").set(RESOURCE.EXTERNAL_ID, "C0DEMONAT1")
				.set(RESOURCE.TAGS, arrayOf("chapter-leads")).set(RESOURCE.MANAGED, "portal").execute()
			rules.create(null, RuleInput("Chapter leads meet in #h4i-chapter-leads", null,
				RuleWho(chapterRoles = listOf("lead", "co_lead")), resources = RuleResources("tags", tags = listOf("chapter-leads"))),
				Viewer(nadia, p.national, "Nadia Okafor", true, emptyMap()))

			// The live projects' resources exist in their tools, and access is in place (fictional IDs; tools stay in dry run).
			for ((projectId, n) in listOf(food to 1, tutor to 2)) {
				dsl.select(RESOURCE.ID, RESOURCE.TOOL, RESOURCE.NAME).from(PROJECT_RESOURCE).join(RESOURCE).on(RESOURCE.ID.eq(PROJECT_RESOURCE.RESOURCE_ID))
					.where(PROJECT_RESOURCE.PROJECT_ID.eq(projectId)).fetch().forEach { r ->
						val external = when (r.value2()) { "slack" -> "C0DEMOPRJ$n"; "github" -> r.value3(); "google" -> r.value3(); else -> "demo-collection-$n" }
						dsl.update(RESOURCE).set(RESOURCE.EXTERNAL_ID, external).where(RESOURCE.ID.eq(r.value1())).execute()
					}
			}
			val world = worlds.load().world
			val created = dsl.select(RESOURCE.ID).from(RESOURCE).where(RESOURCE.EXTERNAL_ID.isNotNull).fetch().map { it.value1()!! }.toSet()
			// A few made-up people haven't accepted their invites yet; the personas have, so their screens are the same every time.
			val personas = setOf(nadia, leo, chloe, victor, priya, marcus)
			Resolver.resolve(world).grants.filter { it.resourceId in created }.forEachIndexed { i, g ->
				dsl.insertInto(SYNC_RECORD).set(SYNC_RECORD.PERSON_ID, g.personId).set(SYNC_RECORD.RESOURCE_ID, g.resourceId).set(SYNC_RECORD.DESIRED, "present")
					.set(SYNC_RECORD.DESIRED_ACCESS_LEVEL, g.access.name.lowercase()).set(SYNC_RECORD.ACTUAL, "present")
					.set(SYNC_RECORD.STATUS, if (i % 11 == 5 && g.personId !in personas) "waiting_on_member" else "in_sync").set(SYNC_RECORD.APPLIED_AT, OffsetDateTime.now().minusDays(3)).execute()
			}

			// Project agreements: the record of who signed (Documenso isn't connected on staging, so the template stays off).
			val template = dsl.insertInto(AGREEMENT_TEMPLATE).set(AGREEMENT_TEMPLATE.KIND, "project").set(AGREEMENT_TEMPLATE.NAME, "Project agreement")
				.set(AGREEMENT_TEMPLATE.DOCUMENSO_TEMPLATE_ID, "envelope_demo").set(AGREEMENT_TEMPLATE.VERSION, 1).set(AGREEMENT_TEMPLATE.ACTIVE, false)
				.returningResult(AGREEMENT_TEMPLATE.ID).fetchSingle().value1()!!
			dsl.select(PROJECT_MEMBER.PERSON_ID, PROJECT_MEMBER.PROJECT_ID, PROJECT_MEMBER.AGREEMENT_STATUS).from(PROJECT_MEMBER).where(PROJECT_MEMBER.PROJECT_ID.`in`(food, tutor)).fetch().forEach {
				val signed = it.value3() == "signed"
				dsl.insertInto(AGREEMENT).set(AGREEMENT.PERSON_ID, it.value1()).set(AGREEMENT.PROJECT_ID, it.value2()).set(AGREEMENT.TEMPLATE_ID, template)
					.set(AGREEMENT.STATUS, if (signed) "signed" else "sent").set(AGREEMENT.SENT_AT, OffsetDateTime.now().minusDays(if (signed) 20 else 4))
					.set(AGREEMENT.SIGNED_AT, if (signed) OffsetDateTime.now().minusDays(18) else null).set(AGREEMENT.REMINDERS_SENT, if (signed) 0 else 1).execute()
			}

			// Registration: the chapter's questions, three applications waiting, two roster invites.
			val why = dsl.insertInto(CHAPTER_QUESTION).set(CHAPTER_QUESTION.CHAPTER_ID, demo).set(CHAPTER_QUESTION.POSITION, 1).set(CHAPTER_QUESTION.LABEL, "Why do you want to join Hack4Impact?")
				.set(CHAPTER_QUESTION.KIND, "long_text").set(CHAPTER_QUESTION.REQUIRED, true).returningResult(CHAPTER_QUESTION.ID).fetchSingle().value1()!!
			val start = dsl.insertInto(CHAPTER_QUESTION).set(CHAPTER_QUESTION.CHAPTER_ID, demo).set(CHAPTER_QUESTION.POSITION, 2).set(CHAPTER_QUESTION.LABEL, "Which semester can you start?")
				.set(CHAPTER_QUESTION.KIND, "choice").set(CHAPTER_QUESTION.OPTIONS, arrayOf("Fall 2026", "Spring 2027")).returningResult(CHAPTER_QUESTION.ID).fetchSingle().value1()!!
			fun application(first: String, last: String, school: String, github: String, year: Int, roleInterest: String, answer: String, match: String, matched: List<UUID>, daysAgo: Long) {
				dsl.insertInto(REGISTRATION).set(REGISTRATION.CHAPTER_ID, demo).set(REGISTRATION.KIND, "new").set(REGISTRATION.STATUS, "submitted")
					.set(REGISTRATION.SCHOOL_EMAIL, school).set(REGISTRATION.EMAIL_VERIFIED_AT, OffsetDateTime.now().minusDays(daysAgo))
					.set(REGISTRATION.FIRST_NAME, first).set(REGISTRATION.LAST_NAME, last).set(REGISTRATION.GRADUATION_YEAR, year).set(REGISTRATION.ROLE_INTEREST, roleInterest)
					.set(REGISTRATION.GITHUB_ID, "${80000 + daysAgo}").set(REGISTRATION.GITHUB_LOGIN, github).set(REGISTRATION.GITHUB_LINKED_AT, OffsetDateTime.now().minusDays(daysAgo))
					.set(REGISTRATION.ANSWERS, JSONB.valueOf("""{"$why":"$answer","$start":"Fall 2026"}"""))
					.set(REGISTRATION.MATCH_STATUS, match).set(REGISTRATION.MATCHED_PERSON_IDS, matched.toTypedArray())
					.set(REGISTRATION.SUBMITTED_AT, OffsetDateTime.now().minusDays(daysAgo)).execute()
			}
			application("Maya", "Johnson", "mayaj@demo.edu", "maya-builds", 2028, "Developer", "I want to use what I learn in class for local nonprofits.", "new", emptyList(), 2)
			application("Hannah", "Moore", "hannahm@demo.edu", "hannah-moore-demo", 2027, "Designer", "I'm back from a semester abroad and want to rejoin.", "alumni", listOf(id("Hannah Moore")), 3)
			application("Benjamin", "Carter", "bcarter2@demo.edu", "bcarter-dev", 2029, "Developer", "A friend on the Food Rescue team told me about it.", "duplicate", listOf(id("Ben Carter")), 1)
			for ((i, name) in listOf("Riley Foster", "Quinn Hayes").withIndex()) {
				val (f, l) = name.split(" ")
				dsl.insertInto(ROSTER_INVITE).set(ROSTER_INVITE.CHAPTER_ID, demo).set(ROSTER_INVITE.UPLOADED_BY, leo).set(ROSTER_INVITE.FIRST_NAME, f).set(ROSTER_INVITE.LAST_NAME, l)
					.set(ROSTER_INVITE.SCHOOL_EMAIL, "${f.lowercase()}${l.first().lowercase()}@demo.edu").set(ROSTER_INVITE.STATUS, if (i == 0) "started" else "sent")
					.set(ROSTER_INVITE.TOKEN_HASH, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("demo-invite-$i".toByteArray())))
					.set(ROSTER_INVITE.EXPIRES_AT, OffsetDateTime.now().plusDays(10)).execute()
			}

			// A second chapter, so national has more than one to look at.
			val sara = person("Sara", "Lindqvist", sample)
			dsl.insertInto(CHAPTER_ROLE).set(CHAPTER_ROLE.PERSON_ID, sara).set(CHAPTER_ROLE.CHAPTER_ID, sample).set(CHAPTER_ROLE.ROLE, "lead").set(CHAPTER_ROLE.TITLE, "Chapter director").execute()
			listOf("Omar Haddad", "Elena Petrova", "Kofi Mensah", "Lucia Fernandez", "Arjun Rao", "Mei Tanaka").forEach { val (f, l) = it.split(" "); person(f, l, sample) }
			val saraViewer = Viewer(sara, "", "Sara Lindqvist", false, mapOf(sample to "lead"))
			val water = projects.create(sample, ProjectInput("Clean Water Map", type = "new-build", tags = listOf("client"), partner = "Sample County Water"), saraViewer)
			listOf("Omar Haddad" to techLead, "Elena Petrova" to dev, "Kofi Mensah" to dev, "Mei Tanaka" to designer).forEach { (who, r) ->
				projects.addMember(sample, dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(water.id)).fetchSingle(), MemberInput(id(who), r), saraViewer)
			}
			projects.setStatus(sample, dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(water.id)).fetchSingle(), "active", saraViewer)

			// National's own project (decision 141): people from both chapters on one team.
			dsl.execute("INSERT INTO portal.chapter (code, name, kind, applications_open) VALUES ('national', 'Hack4Impact National', 'national', false) ON CONFLICT DO NOTHING")
			val nationalChapter = dsl.select(CHAPTER.ID).from(CHAPTER).where(CHAPTER.KIND.eq("national")).fetchSingle().value1()!!
			val nadiaViewer = Viewer(nadia, p.national, "Nadia Okafor", true, emptyMap())
			val kit = projects.create(nationalChapter, ProjectInput("Chapter Starter Kit", type = "internal", tags = listOf("national"), partner = "Hack4Impact"), nadiaViewer)
			listOf("Arjun Rao" to techLead, "Grace Lee" to dev, "Lucia Fernandez" to designer).forEach { (who, r) ->
				projects.addMember(nationalChapter, dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(kit.id)).fetchSingle(), MemberInput(id(who), r), nadiaViewer)
			}
			projects.setStatus(nationalChapter, dsl.selectFrom(PROJECT).where(PROJECT.ID.eq(kit.id)).fetchSingle(), "active", nadiaViewer)
			dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.AGREEMENT_STATUS, "signed").where(PROJECT_MEMBER.PROJECT_ID.eq(kit.id)).execute()

			return Triple(demo, leoViewer, listOf("Jack Turner" to techLead, "Lily Evans" to dev, "Mason Wright" to dev, "Zoe Mitchell" to designer).map { (who, r) -> id(who) to r })
		}
	}
}

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DemoProperties::class)
class DemoSeedConfiguration
