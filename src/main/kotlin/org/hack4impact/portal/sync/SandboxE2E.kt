package org.hack4impact.portal.sync

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_RESOURCE
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_MEMBER
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.db.tables.references.SYNC_CHANGE
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.db.tables.references.TOOL_SETTING
import org.hack4impact.portal.resolver.Tool
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.sql.DataSource
import kotlin.system.exitProcess

@ConfigurationProperties("portal.sandbox-e2e")
data class SandboxE2EProperties(
	val enabled: Boolean = false,
	val fixture: String = "sandbox-fixture.yml",
	/** Must be true: the suite empties the local portal database first. */
	val resetDatabase: Boolean = false,
	/** Leave the created channel, team and group in the sandboxes (to look at them). */
	val keep: Boolean = false,
	/** Registration phase (step 10): the verified Workspace domain test accounts are created at. */
	val accountDomain: String = "hack4impact.org",
	/** Agreements phase (step 11): the project agreement template's envelope ID in staging Documenso. */
	val agreementTemplate: String = "",
	/** How long to wait for someone to sign the test agreement by hand (the step 11 finish line); zero: don't wait. */
	val waitForSignature: java.time.Duration = java.time.Duration.ZERO,
)

/**
 * The step 9 finish line: really changes the sandboxes, through the sync engine, and checks each tool.
 *
 *  1. Loads the fixture's people into an emptied LOCAL database and gives them a new project whose channel,
 *     group and team exist only in the portal, then takes those tools out of dry run (in this database only).
 *  2. Apply: the run creates the resources and adds everyone it can; the tools must show them.
 *  3. Again: a second run must have nothing left to do.
 *  4. Rollback: everyone leaves the project; the run must take away what it gave, and the tools must show that.
 *  4b. Rules (step 14): a chapter rule gives everyone the test channel and team, previewed first; turning it off
 *     takes exactly that back.
 *  5. Cleanup: the created resources are archived or deleted (unless --portal.sandbox-e2e.keep=true).
 *
 * Tools with a write adapter (`PORTAL_ADAPTERS_<TOOL>_WRITE=true`) take part, plus Notion through the admin queue:
 * a test teamspace for the chapter, whose "add" and "remove" tasks the suite marks done the way national would.
 * Run with scripts/sandbox-e2e.sh.
 */
@Component
@ConditionalOnBooleanProperty("portal.sandbox-e2e.enabled")
class SandboxE2E(
	private val properties: SandboxE2EProperties,
	private val dsl: DSLContext,
	private val dataSource: DataSource,
	private val readers: List<ReadAdapter>,
	private val writers: List<WriteAdapter>,
	private val engine: SyncEngine,
	private val queue: org.hack4impact.portal.national.AdminQueue,
	private val registrations: org.hack4impact.portal.registration.RegistrationService,
	private val accounts: org.springframework.beans.factory.ObjectProvider<org.hack4impact.portal.adapters.google.GoogleAccounts>,
	private val adapterProperties: org.hack4impact.portal.adapters.AdapterProperties,
	private val agreements: org.hack4impact.portal.agreements.AgreementService,
	private val drafts: org.hack4impact.portal.drafts.Drafts,
	private val rules: org.hack4impact.portal.rules.Rules,
	private val documenso: org.springframework.beans.factory.ObjectProvider<org.hack4impact.portal.adapters.documenso.DocumensoClient>,
	private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
	private val log = LoggerFactory.getLogger(javaClass)
	private val report = StringBuilder()
	private var failures = 0

	private fun check(tool: Tool, step: String, ok: Boolean, detail: String = "") = check(tool.name, step, ok, detail)

	private fun check(label: String, step: String, ok: Boolean, detail: String = "") {
		if (!ok) failures++
		report.appendLine("${if (ok) "PASS" else "FAIL"}  ${label.padEnd(6)} $step${if (detail.isNotEmpty()) "  ($detail)" else ""}")
	}

	override fun run(args: ApplicationArguments) {
		val code = try {
			if (suite()) 0 else 1
		} catch (e: IllegalStateException) {
			log.error("Sandbox suite stopped: {}", e.message); 1
		} catch (e: IllegalArgumentException) {
			log.error("Sandbox suite stopped: {}", e.message); 1
		}
		exitProcess(SpringApplication.exit(context, { code }))
	}

	@Suppress("UNCHECKED_CAST")
	fun suite(): Boolean {
		val url = dataSource.connection.use { it.metaData.url }
		check("//localhost" in url || "//127.0.0.1" in url) { "refusing to reset a non-local database ($url)" }
		check(properties.resetDatabase) { "add --portal.sandbox-e2e.reset-database=true: the suite empties the local portal database first" }
		val file = File(properties.fixture)
		require(file.isFile) { "no fixture at ${file.absolutePath} (copy sandbox-fixture.example.yml)" }
		val fixture = Yaml().load<Map<String, Any?>>(file.readText())
		val readerOf = readers.associateBy { it.tool }
		val tools = writers.map { it.tool }.filter { it in readerOf && it in setOf(Tool.SLACK, Tool.GITHUB, Tool.GOOGLE, Tool.VAULTWARDEN, Tool.NOTION) }.sorted()
		check(tools.isNotEmpty()) { "no write adapters: set PORTAL_ADAPTERS_<TOOL>_WRITE=true for the sandboxes" }

		// 1. Setup.
		resetDatabase()
		val chapter = fixture["chapter"] as Map<String, Any?>
		val code = chapter["code"] as String
		val chapterId = dsl.insertInto(CHAPTER).set(CHAPTER.CODE, code).set(CHAPTER.NAME, chapter["name"] as String).returningResult(CHAPTER.ID).fetchSingle().value1()!!
		val people = loadFixturePeople(dsl, fixture, readerOf, chapterId, skipDisabledTools = true)
		val run = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
		val slug = "e2e-$run"
		// A project not started yet and without its team: the team plan comes through a draft (1b).
		val project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, chapterId).set(PROJECT.NAME, "E2E $run").set(PROJECT.SLUG, slug).set(PROJECT.STATUS, "draft")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		val active = people.filterKeys { name -> (fixture["people"] as List<Map<String, Any?>>).first { it["name"] == name }["status"].let { it == null || it == "active" } }
		val resources = tools.associateWith { tool ->
			if (tool == Tool.NOTION) {
				// A teamspace exists already (the portal can't create one); its members come through the queue.
				val id = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, "notion").set(RESOURCE.NAME, "E2E teamspace $run").set(RESOURCE.EXTERNAL_ID, "e2e-teamspace-$run")
					.set(RESOURCE.CHAPTER_ID, chapterId).set(RESOURCE.TAGS, arrayOf("teamspace")).returningResult(RESOURCE.ID).fetchSingle().value1()!!
				dsl.insertInto(CHAPTER_RESOURCE).set(CHAPTER_RESOURCE.CHAPTER_ID, chapterId).set(CHAPTER_RESOURCE.RESOURCE_ID, id)
					.set(CHAPTER_RESOURCE.AUDIENCE, "members").set(CHAPTER_RESOURCE.ACCESS_LEVEL, "write").execute()
				return@associateWith id
			}
			val name = if (tool == Tool.GOOGLE) "$code-$slug@hack4impact.org" else "$code-$slug"
			val id = dsl.insertInto(RESOURCE).set(RESOURCE.TOOL, tool.name.lowercase()).set(RESOURCE.NAME, name).set(RESOURCE.CHAPTER_ID, chapterId)
				.returningResult(RESOURCE.ID).fetchSingle().value1()!!
			dsl.insertInto(PROJECT_RESOURCE).set(PROJECT_RESOURCE.PROJECT_ID, project).set(PROJECT_RESOURCE.RESOURCE_ID, id)
				.set(PROJECT_RESOURCE.AUDIENCE, "team").set(PROJECT_RESOURCE.ACCESS_LEVEL, "write").execute()
			id
		}
		// National, marking the queue's tasks done after doing them by hand.
		val national = org.hack4impact.portal.auth.Viewer(null, "sandbox-e2e", "Sandbox suite", true, emptyMap())
		fun doQueuedTasks(action: String): Int {
			val open = dsl.select(org.hack4impact.portal.db.tables.references.ADMIN_TASK.ID).from(org.hack4impact.portal.db.tables.references.ADMIN_TASK)
				.where(org.hack4impact.portal.db.tables.references.ADMIN_TASK.ACTION.eq(action), org.hack4impact.portal.db.tables.references.ADMIN_TASK.STATUS.eq("open"))
				.fetch().map { it.value1()!! }
			open.forEach { queue.done(it, national) }
			return open.size
		}
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.`in`(tools.map { it.name.lowercase() })).execute()

		// 1b. The semester's team plan (step 12): everyone onto the project and the project started, in a draft that's
		// previewed and then released in one transaction.
		releasePhase(chapterId, project, slug, active.values.toList(), run)
		// 1c. Registration (step 10): a returning applicant is approved and gets a real Google account, which the
		// apply phase below then gives the chapter's access. Needs PORTAL_ADAPTERS_GOOGLE_CREATEACCOUNTS and USERSCOPE.
		val createdAccount = registrationPhase(chapterId, active.values.toList(), run)
		// 1d. Agreements (step 11): one member's project agreement goes out through staging Documenso, and the GitHub
		// team waits for their signature.
		val signer = agreementPhase(project, active.values.toList(), resources, run)
		report.appendLine("Run $run: ${tools.joinToString { it.name.lowercase() }}, ${active.size} people, resources named $code-$slug")

		// The accounts each person has in each tool: the fixture's, plus the one a registration created.
		fun accountsOf(tool: Tool, person: UUID) = dsl.select(TOOL_ACCOUNT.EXTERNAL_ID).from(TOOL_ACCOUNT)
			.where(TOOL_ACCOUNT.PERSON_ID.eq(person), TOOL_ACCOUNT.TOOL.eq(tool.name.lowercase())).fetch().mapNotNull { it.value1() }
		fun externalId(tool: Tool) = dsl.select(RESOURCE.EXTERNAL_ID).from(RESOURCE).where(RESOURCE.ID.eq(resources.getValue(tool))).fetchSingle().value1()
		fun outcomes(runId: UUID?) = dsl.select(SYNC_CHANGE.KIND, SYNC_CHANGE.OUTCOME, SYNC_CHANGE.PERSON_ID, SYNC_CHANGE.ERROR).from(SYNC_CHANGE)
			.where(SYNC_CHANGE.RUN_ID.eq(runId)).fetch()
		fun membersIn(tool: Tool): Set<String>? = externalId(tool)?.let { ext ->
			try { readerOf.getValue(tool).members(ext).map { it.accountId }.toSet() } catch (e: AdapterException) { null }
		}

		try {
			// 2. Apply.
			val applied = mutableMapOf<Tool, Set<String>>()
			for (r in engine.run("manual", tools.toSet())) {
				val changes = outcomes(r.runId)
				check(r.tool, "apply: run ${r.status}", r.status == "completed", r.error ?: "")
				if (r.tool == Tool.NOTION) {
					val queued = changes.count { it.value1() == "add" && it.value2() == "queued" }
					check(r.tool, "apply: queued $queued add tasks for ${active.size} people", queued == active.size)
					val done = doQueuedTasks("add_member")
					val there = membersIn(r.tool)
					check(r.tool, "apply: marked $done done, and the teamspace counts them", there != null && there.size == active.size, "${there?.size ?: 0} members")
					applied[r.tool] = there.orEmpty()
					continue
				}
				val created = changes.any { it.value1() == "create_resource" && it.value2() == "applied" }
				check(r.tool, "apply: created ${externalId(r.tool) ?: "nothing"}", created, changes.firstOrNull { it.value1() == "create_resource" }?.value4() ?: "")
				val there = membersIn(r.tool)
				// Per person added, the account that got access (someone may have two: the fixture's and a new H4I one).
				val done = changes.filter { it.value1() == "add" && it.value2() == "applied" }.mapNotNull { it.value3() }
					.mapNotNull { p -> accountsOf(r.tool, p).let { mine -> mine.firstOrNull { there?.contains(it) == true } ?: mine.firstOrNull() } }.toSet()
				val other = changes.filter { it.value1() == "add" && it.value2() != "applied" }.map { "${it.value2()}${it.value4()?.let { e -> ": $e" } ?: ""}" }
				check(r.tool, "apply: added ${done.size} of ${active.size}", done.isNotEmpty(), other.joinToString().ifEmpty { "everyone" })
				check(r.tool, "apply: the tool shows them", there != null && there.containsAll(done), if (there == null) "couldn't read members" else "${(done - there).size} missing")
				applied[r.tool] = done
				if (r.tool == Tool.GOOGLE && createdAccount != null) {
					val row = dsl.select(TOOL_ACCOUNT.EXTERNAL_ID, TOOL_ACCOUNT.PERSON_ID).from(TOOL_ACCOUNT).where(TOOL_ACCOUNT.TOOL.eq("google"), TOOL_ACCOUNT.EXTERNAL_LOGIN.eq(createdAccount)).fetchOne()
					val id = row?.value1()
					val createdPerson = row?.value2()
					val ok = id != null && there != null && id in there
					val given = dsl.select(SYNC_CHANGE.ACCOUNT_ID).from(SYNC_CHANGE).where(SYNC_CHANGE.RUN_ID.eq(r.runId), SYNC_CHANGE.KIND.eq("add"), SYNC_CHANGE.PERSON_ID.eq(createdPerson)).fetch().map { it.value1() }
					val listed = if (ok) "" else try {
						"given to: ${given.joinToString { if (it == id) "the new account" else "another account" }}; listed by Google now: ${readerOf.getValue(r.tool).accounts().any { it.externalId == id }}"
					} catch (e: AdapterException) { e.message ?: "" }
					check(r.tool, "apply: the applicant's new H4I account got the access", ok, "$createdAccount $listed".trim())
				}
			}

			if (signer != null) {
				val held = accountsOf(Tool.GITHUB, signer.person).none { membersIn(Tool.GITHUB)?.contains(it) == true }
				check(Tool.GITHUB, "agreement: held back from the team until signed", held, signer.email)
			}

			// 3. Again: nothing left to do for those already added.
			for (r in engine.run("manual", tools.toSet())) {
				val repeated = outcomes(r.runId).filter { (it.value1() == "add" || it.value1() == "create_resource") && it.value2() in setOf("applied", "queued") }
				check(r.tool, "again: nothing left to do", repeated.isEmpty(), "${repeated.size} changes repeated")
			}

			// 3b. The step 11 finish line: someone signs the test agreement by hand, and the signature releases the repo.
			if (signer != null && !properties.waitForSignature.isZero) {
				log.info("Sandbox suite: sign the test agreement now ({}): {} (or the email to {} in Mailpit, http://localhost:8025). Waiting up to {}.",
					run, signer.signingUrl, signer.email, properties.waitForSignature)
				val until = System.currentTimeMillis() + properties.waitForSignature.toMillis()
				fun signed() = dsl.select(PROJECT_MEMBER.AGREEMENT_STATUS).from(PROJECT_MEMBER)
					.where(PROJECT_MEMBER.PROJECT_ID.eq(project), PROJECT_MEMBER.PERSON_ID.eq(signer.person)).fetchSingle().value1() == "signed"
				while (!signed() && System.currentTimeMillis() < until) {
					Thread.sleep(10_000)
					agreements.pass()
				}
				check(Tool.DOCUMENSO, "agreement: signed, and the portal saw it", signed(), signer.email)
				if (signed()) {
					val pdf = dsl.select(org.hack4impact.portal.db.tables.references.AGREEMENT.PDF_LOCATION).from(org.hack4impact.portal.db.tables.references.AGREEMENT)
						.where(org.hack4impact.portal.db.tables.references.AGREEMENT.DOCUMENSO_DOCUMENT_ID.eq(signer.envelopeId)).fetchSingle().value1()
					check(Tool.DOCUMENSO, "agreement: the signed PDF is archived in the portal", pdf == "database")
					val release = engine.run("agreement", setOf(Tool.GITHUB)).single()
					val there = membersIn(Tool.GITHUB)
					val account = accountsOf(Tool.GITHUB, signer.person).firstOrNull { there?.contains(it) == true }
					check(Tool.GITHUB, "agreement: the signature released the repo access", release.status == "completed" && account != null, release.error ?: "")
					account?.let { applied[Tool.GITHUB] = applied[Tool.GITHUB].orEmpty() + it }
				}
			} else if (signer != null) report.appendLine("   Agreement not signed by hand (add --portal.sandbox-e2e.wait-for-signature=PT15M for the step 11 finish line)")

			// 4. Rollback: everyone leaves the project, and the chapter drops the test teamspace.
			dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.REMOVED_AT, OffsetDateTime.now()).where(PROJECT_MEMBER.PROJECT_ID.eq(project)).execute()
			resources[Tool.NOTION]?.let { dsl.deleteFrom(CHAPTER_RESOURCE).where(CHAPTER_RESOURCE.RESOURCE_ID.eq(it)).execute() }
			for (r in engine.run("manual", tools.toSet())) {
				if (r.tool == Tool.NOTION) {
					val queued = outcomes(r.runId).count { it.value1() == "remove" && it.value2() == "queued" }
					check(r.tool, "rollback: queued $queued remove tasks", queued == applied[r.tool].orEmpty().size)
					doQueuedTasks("remove_member")
					check(r.tool, "rollback: marked done, and the teamspace counts them gone", membersIn(r.tool)?.isEmpty() == true)
					continue
				}
				val removed = outcomes(r.runId).filter { it.value1() == "remove" && it.value2() == "applied" }.size
				// Access someone has through their role (a Vaultwarden owner) isn't the portal's to take away.
				val implicit = externalId(r.tool)?.let { ext ->
					try { readerOf.getValue(r.tool).members(ext).filter { it.implicit }.map { it.accountId }.toSet() } catch (e: AdapterException) { emptySet() }
				}.orEmpty()
				val expected = applied[r.tool].orEmpty() - implicit
				if (implicit.isNotEmpty()) report.appendLine("   ${r.tool}: ${implicit.size} with access through their role (kept)")
				check(r.tool, "rollback: removed $removed of ${expected.size}", r.status == "completed" && removed == expected.size, r.error ?: "")
				val there = membersIn(r.tool)
				check(r.tool, "rollback: the tool shows them gone", there != null && there.intersect(expected).isEmpty(), if (there == null) "couldn't read members" else "${there.intersect(expected).size} still there")
			}

			// 4b. Automations (step 14): with the project's access gone, a chapter rule gives everyone read on the test
			// channel and team. Its preview must match what the sync then does, and turning it off must take back exactly that.
			val ruleTools = tools.filter { it == Tool.SLACK || it == Tool.GITHUB }
			if (ruleTools.isEmpty()) report.appendLine("Rules: skipped (needs Slack or GitHub)") else {
				val tag = "e2e-rule-$run"
				dsl.update(RESOURCE).set(RESOURCE.TAGS, arrayOf(tag)).where(RESOURCE.ID.`in`(ruleTools.map { resources.getValue(it) })).execute()
				// The rule's resources aren't behind the project agreement here (the agreement phase gated the team).
				dsl.update(PROJECT_RESOURCE).set(PROJECT_RESOURCE.REQUIRES_AGREEMENT, false).where(PROJECT_RESOURCE.PROJECT_ID.eq(project)).execute()
				val leadId = active.values.first()
				val leadRow = dsl.selectFrom(org.hack4impact.portal.db.tables.references.PERSON).where(org.hack4impact.portal.db.tables.references.PERSON.ID.eq(leadId)).fetchSingle()
				val lead = org.hack4impact.portal.auth.Viewer(leadId, leadRow.orgEmail ?: "", "${leadRow.firstName} ${leadRow.lastName}", false, mapOf(chapterId to "lead"))
				val input = org.hack4impact.portal.rules.RuleInput(
					"E2E $run: everyone in the test channel and team", who = org.hack4impact.portal.rules.RuleWho(everyone = true),
					resources = org.hack4impact.portal.rules.RuleResources("tags", tags = listOf(tag)), tools = ruleTools.map { it.name.lowercase() },
				)
				val on = rules.preview(chapterId, org.hack4impact.portal.rules.RulePreviewInput(input = input), lead).preview
				check("RULE", "preview: adds ${on.people} people to ${on.resources} resources", on.adds == on.people * ruleTools.size && on.resources == ruleTools.size && on.removes == 0, "${on.adds} grants")
				val ruleId = rules.create(chapterId, input, lead)
				val given = mutableMapOf<Tool, Set<String>>()
				for (r in engine.run("rule", ruleTools.toSet())) {
					val there = membersIn(r.tool)
					val done = outcomes(r.runId).filter { it.value1() == "add" && it.value2() == "applied" }.mapNotNull { it.value3() }
						.mapNotNull { p -> accountsOf(r.tool, p).let { mine -> mine.firstOrNull { there?.contains(it) == true } ?: mine.firstOrNull() } }.toSet()
					check(r.tool, "rule: added ${done.size}, and the tool shows them", r.status == "completed" && done.isNotEmpty() && there != null && there.containsAll(done), r.error ?: "")
					given[r.tool] = done
				}
				val off = rules.preview(chapterId, org.hack4impact.portal.rules.RulePreviewInput(ruleId, input.copy(enabled = false)), lead).preview
				check("RULE", "turning it off previews taking back all ${on.adds} grants", off.removes == on.adds && off.adds == 0, "${off.removes} removals")
				rules.setEnabled(chapterId, ruleId, false, lead)
				for (r in engine.run("rule", ruleTools.toSet())) {
					val removed = outcomes(r.runId).filter { it.value1() == "remove" && it.value2() == "applied" }.size
					val implicit = externalId(r.tool)?.let { ext ->
						try { readerOf.getValue(r.tool).members(ext).filter { it.implicit }.map { it.accountId }.toSet() } catch (e: AdapterException) { emptySet() }
					}.orEmpty()
					val expected = given[r.tool].orEmpty() - implicit
					val there = membersIn(r.tool)
					check(r.tool, "rule off: removed $removed of ${expected.size}, and the tool shows them gone",
						r.status == "completed" && removed == expected.size && there != null && there.intersect(expected).isEmpty(), r.error ?: "")
				}
			}
		} catch (e: RuntimeException) {
			// Still clean up and report: a crash mustn't leave real accounts and groups behind unnoticed.
			log.error("Sandbox suite stopped early", e)
			failures++
			report.appendLine("FAIL  stopped early: ${e.javaClass.simpleName}: ${e.message}")
		} finally {
			// 5. Cleanup.
			signer?.let { a ->
				val client = documenso.ifAvailable!!
				val ok = try {
					runCatching { client.cancel(a.envelopeId, "Sandbox suite cleanup") } // a signed one can't be cancelled; deleting is enough
					client.delete(a.envelopeId); true
				} catch (e: AdapterException) { report.appendLine("   cleanup agreement: ${e.message}"); false }
				check(Tool.DOCUMENSO, "cleanup: deleted the test agreement", ok)
			}
			createdAccount?.let { email ->
				val ok = try { accounts.ifAvailable?.delete(email); true } catch (e: AdapterException) { report.appendLine("   cleanup account: ${e.message}"); false }
				check(Tool.GOOGLE, "cleanup: deleted the test account $email", ok)
			}
			if (!properties.keep) {
				for (tool in tools.filter { it != Tool.NOTION }) {
					val ext = externalId(tool) ?: continue
					val ok = try { writers.first { it.tool == tool }.archive(ext); true } catch (e: AdapterException) { report.appendLine("   cleanup $tool: ${e.message}"); false }
					check(tool, "cleanup: ${if (tool == Tool.SLACK) "archived" else "deleted"} ${if (tool == Tool.VAULTWARDEN) "collection $ext" else ext}", ok)
				}
			} else report.appendLine("--keep: left the created resources in the sandboxes")
		}
		report.appendLine(if (failures == 0) "ALL PASSED" else "$failures FAILED")
		log.info("Sandbox suite:\n{}", report)
		return failures == 0
	}

	/** The step 12 finish line: a semester's team plan goes through a draft and releases in one transaction. */
	private fun releasePhase(chapterId: UUID, project: UUID, slug: String, people: List<UUID>, run: String) {
		val leadId = people.first()
		val lead = dsl.selectFrom(org.hack4impact.portal.db.tables.references.PERSON).where(org.hack4impact.portal.db.tables.references.PERSON.ID.eq(leadId)).fetchSingle()
		val viewer = org.hack4impact.portal.auth.Viewer(leadId, lead.orgEmail ?: "", "${lead.firstName} ${lead.lastName}", false, mapOf(chapterId to "lead"))
		val draft = drafts.create(chapterId, "E2E $run teams", viewer).id
		people.forEach { person ->
			drafts.record(chapterId, draft, viewer, slug) { p -> drafts.operation("add_member", p!!.id!!, "input" to org.hack4impact.portal.projects.MemberInput(person)) }
		}
		drafts.record(chapterId, draft, viewer, slug) { p -> drafts.operation("set_status", p!!.id!!, "status" to "active") }
		fun members() = dsl.fetchCount(PROJECT_MEMBER, PROJECT_MEMBER.PROJECT_ID.eq(project), PROJECT_MEMBER.REMOVED_AT.isNull)
		fun status() = dsl.select(PROJECT.STATUS).from(PROJECT).where(PROJECT.ID.eq(project)).fetchSingle().value1()
		check("DRAFT", "planned: ${people.size + 1} changes, nothing live yet", members() == 0 && status() == "draft")
		val detail = drafts.detail(chapterId, draft, viewer)
		val p = detail.preview
		check("DRAFT", "preview: adds ${p.people} people to ${p.resources} resources", detail.conflicts.isEmpty() && p.perPerson.size == people.size && p.adds > 0,
			detail.conflicts.joinToString { it.problem }.ifEmpty { "${p.adds} grants" })
		val queued = { dsl.fetchCount(dsl.selectFrom("portal.event_publication").where("serialized_event LIKE '%\"trigger\":\"release\"%'")) }
		val before = queued()
		val result = drafts.release(chapterId, draft, viewer)
		check("DRAFT", "released in one transaction: ${members()} people on the project, project ${status()}, sync queued",
			result.released && members() == people.size && status() == "active" && queued() > before, result.conflicts.joinToString { it.problem })
		// The suite runs the syncs itself. Everyone counts as having signed; the agreement phase un-signs one person.
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.AGREEMENT_STATUS, "signed").where(PROJECT_MEMBER.PROJECT_ID.eq(project)).execute()
	}

	private data class AgreementRun(val person: UUID, val email: String, val envelopeId: String, val signingUrl: String?)

	/** Sends one member's project agreement through staging Documenso; the project's GitHub team waits for it. */
	private fun agreementPhase(project: UUID, people: List<UUID>, resources: Map<Tool, UUID>, run: String): AgreementRun? {
		val client = documenso.ifAvailable
		if (client == null || !adapterProperties.documenso.write || properties.agreementTemplate.isBlank() || Tool.GITHUB !in resources) {
			report.appendLine("Agreements: skipped (needs PORTAL_ADAPTERS_DOCUMENSO_* with WRITE=true, PORTAL_SANDBOXE2E_AGREEMENTTEMPLATE and GitHub)")
			return null
		}
		val person = people.firstOrNull { p -> dsl.fetchExists(TOOL_ACCOUNT, TOOL_ACCOUNT.PERSON_ID.eq(p), TOOL_ACCOUNT.TOOL.eq("github"), TOOL_ACCOUNT.EXTERNAL_ID.isNotNull) } ?: run {
			report.appendLine("Agreements: skipped (no fixture person with a GitHub account)")
			return null
		}
		val agreement = org.hack4impact.portal.db.tables.references.AGREEMENT
		val template = org.hack4impact.portal.db.tables.references.AGREEMENT_TEMPLATE
		dsl.insertInto(template).set(template.KIND, "project").set(template.NAME, "Project agreement (sandbox $run)")
			.set(template.DOCUMENSO_TEMPLATE_ID, properties.agreementTemplate.trim()).set(template.VERSION, 1).execute()
		dsl.update(TOOL_SETTING).set(TOOL_SETTING.DRY_RUN, false).where(TOOL_SETTING.TOOL.eq("documenso")).execute()
		dsl.update(PROJECT_RESOURCE).set(PROJECT_RESOURCE.REQUIRES_AGREEMENT, true).where(PROJECT_RESOURCE.RESOURCE_ID.eq(resources.getValue(Tool.GITHUB))).execute()
		dsl.update(PROJECT_MEMBER).set(PROJECT_MEMBER.AGREEMENT_STATUS, "pending").where(PROJECT_MEMBER.PROJECT_ID.eq(project), PROJECT_MEMBER.PERSON_ID.eq(person)).execute()
		agreements.pass()
		val a = dsl.selectFrom(agreement).where(agreement.PERSON_ID.eq(person), agreement.PROJECT_ID.eq(project)).fetchOne()
		check(Tool.DOCUMENSO, "agreement: sent", a?.status == "sent", a?.error ?: "")
		if (a?.status != "sent") return null
		val envelope = try { client.envelope(a.documensoDocumentId!!) } catch (e: AdapterException) { null }
		val signer = envelope?.signers?.firstOrNull()
		check(Tool.DOCUMENSO, "agreement: Documenso has it waiting for a signature", envelope?.status == "PENDING" && signer != null, envelope?.status ?: "couldn't read it")
		return AgreementRun(person, signer?.email ?: "", a.documensoDocumentId!!, signer?.signingUrl)
	}

	/** Returns the created account's address, or null when the phase didn't run. */
	private fun registrationPhase(chapterId: UUID, people: List<UUID>, run: String): String? {
		val google = accounts.ifAvailable
		val scope = adapterProperties.google.userScope.takeIf { it.isNotBlank() }?.let(org.hack4impact.portal.adapters.google.GoogleAccounts::orgUnitPath).orEmpty()
		if (google == null || scope.isEmpty()) {
			report.appendLine("Registration: skipped (needs PORTAL_ADAPTERS_GOOGLE_CREATEACCOUNTS=true and PORTAL_ADAPTERS_GOOGLE_USERSCOPE)")
			return null
		}
		// A fixture person with a sandbox GitHub account returns: same GitHub account, so the match is "returning".
		val (person, github) = people.firstNotNullOfOrNull { p ->
			dsl.select(org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT.EXTERNAL_ID, org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT.EXTERNAL_LOGIN)
				.from(org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT)
				.where(org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT.PERSON_ID.eq(p), org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT.TOOL.eq("github"))
				.fetchOne()?.let { p to org.hack4impact.portal.registration.GitHubUser(it.value1()!!, it.value2() ?: it.value1()!!) }
		} ?: run {
			report.appendLine("Registration: skipped (no fixture person with a GitHub account)")
			return null
		}
		// Accounts an earlier run created but couldn't delete (it stopped early): the applicant is always "E2e<run>".
		val leftovers = try { google.accountsInScope("e2e").filter { Regex("^e2e\\d{12}\\d*@").containsMatchIn(it) } } catch (e: AdapterException) { emptyList() }
		if (leftovers.isNotEmpty()) {
			val deleted = leftovers.count { try { google.delete(it); true } catch (e: AdapterException) { false } }
			check(Tool.GOOGLE, "registration: deleted $deleted of ${leftovers.size} test accounts left by earlier runs", deleted == leftovers.size)
		}
		dsl.update(CHAPTER).set(CHAPTER.GOOGLE_DOMAIN, properties.accountDomain).set(CHAPTER.GOOGLE_ORG_UNIT, scope).where(CHAPTER.ID.eq(chapterId)).execute()
		val reg = org.hack4impact.portal.db.tables.references.REGISTRATION
		// The school-email code is covered by the API tests; here the address counts as verified.
		val registration = dsl.insertInto(reg).set(reg.CHAPTER_ID, chapterId).set(reg.KIND, "new").set(reg.SCHOOL_EMAIL, "e2e-$run@example.edu")
			.set(reg.EMAIL_VERIFIED_AT, OffsetDateTime.now()).returningResult(reg.ID).fetchSingle().value1()!!
		registrations.linkGitHub(registration, github) // what the OAuth callback does
		registrations.save(registration, org.hack4impact.portal.registration.DetailsInput(org.hack4impact.portal.registration.Details("E2e$run", "Applicant", graduationYear = 2030, graduationSeason = "spring", major = "Computer Science", degreeType = "Bachelor's")))
		registrations.submit(registration)
		val match = dsl.select(reg.MATCH_STATUS).from(reg).where(reg.ID.eq(registration)).fetchSingle().value1()
		check(Tool.GOOGLE, "registration: matched as $match", match == "returning")
		registrations.approve(chapterId, registration, org.hack4impact.portal.registration.ApproveInput(person), null)
		val email = dsl.select(org.hack4impact.portal.db.tables.references.PERSON.ORG_EMAIL).from(org.hack4impact.portal.db.tables.references.PERSON)
			.where(org.hack4impact.portal.db.tables.references.PERSON.ID.eq(person)).fetchSingle().value1()
		check(Tool.GOOGLE, "registration: approved, account ${email ?: "not created"}", email != null && email.endsWith("@${properties.accountDomain}"))
		return email
	}

	private fun resetDatabase() {
		val tables = dsl.fetch("SELECT tablename FROM pg_tables WHERE schemaname = 'portal' AND tablename <> 'flyway_schema_history'").map { it.get(0, String::class.java) }
		dsl.execute("TRUNCATE " + tables.joinToString { "portal.\"$it\"" } + " CASCADE")
		dsl.execute("INSERT INTO portal.tool_setting (tool) VALUES ('google'), ('github'), ('slack'), ('notion'), ('vaultwarden'), ('documenso') ON CONFLICT DO NOTHING")
	}
}
