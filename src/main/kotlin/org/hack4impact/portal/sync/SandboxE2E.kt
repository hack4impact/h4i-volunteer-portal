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
)

/**
 * The step 9 finish line: really changes the sandboxes, through the sync engine, and checks each tool.
 *
 *  1. Loads the fixture's people into an emptied LOCAL database and gives them a new project whose channel,
 *     group and team exist only in the portal, then takes those tools out of dry run (in this database only).
 *  2. Apply: the run creates the resources and adds everyone it can; the tools must show them.
 *  3. Again: a second run must have nothing left to do.
 *  4. Rollback: everyone leaves the project; the run must take away what it gave, and the tools must show that.
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
	private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
	private val log = LoggerFactory.getLogger(javaClass)
	private val report = StringBuilder()
	private var failures = 0

	private fun check(tool: Tool, step: String, ok: Boolean, detail: String = "") {
		if (!ok) failures++
		report.appendLine("${if (ok) "PASS" else "FAIL"}  ${tool.name.padEnd(6)} $step${if (detail.isNotEmpty()) "  ($detail)" else ""}")
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
		val project = dsl.insertInto(PROJECT).set(PROJECT.CHAPTER_ID, chapterId).set(PROJECT.NAME, "E2E $run").set(PROJECT.SLUG, slug).set(PROJECT.STATUS, "active")
			.returningResult(PROJECT.ID).fetchSingle().value1()!!
		val active = people.filterKeys { name -> (fixture["people"] as List<Map<String, Any?>>).first { it["name"] == name }["status"].let { it == null || it == "active" } }
		active.values.forEach {
			dsl.insertInto(PROJECT_MEMBER).set(PROJECT_MEMBER.PROJECT_ID, project).set(PROJECT_MEMBER.PERSON_ID, it).set(PROJECT_MEMBER.AGREEMENT_STATUS, "signed").execute()
		}
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
		report.appendLine("Run $run: ${tools.joinToString { it.name.lowercase() }}, ${active.size} people, resources named $code-$slug")

		// The account each person has in each tool, from the fixture.
		fun accountOf(tool: Tool, person: UUID) = dsl.select(TOOL_ACCOUNT.EXTERNAL_ID).from(TOOL_ACCOUNT)
			.where(TOOL_ACCOUNT.PERSON_ID.eq(person), TOOL_ACCOUNT.TOOL.eq(tool.name.lowercase())).fetchOne()?.value1()
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
				val done = changes.filter { it.value1() == "add" && it.value2() == "applied" }.mapNotNull { it.value3()?.let { p -> accountOf(r.tool, p) } }.toSet()
				val other = changes.filter { it.value1() == "add" && it.value2() != "applied" }.map { "${it.value2()}${it.value4()?.let { e -> ": $e" } ?: ""}" }
				check(r.tool, "apply: added ${done.size} of ${active.size}", done.isNotEmpty(), other.joinToString().ifEmpty { "everyone" })
				val there = membersIn(r.tool)
				check(r.tool, "apply: the tool shows them", there != null && there.containsAll(done), if (there == null) "couldn't read members" else "${(done - there).size} missing")
				applied[r.tool] = done
			}

			// 3. Again: nothing left to do for those already added.
			for (r in engine.run("manual", tools.toSet())) {
				val repeated = outcomes(r.runId).filter { (it.value1() == "add" || it.value1() == "create_resource") && it.value2() in setOf("applied", "queued") }
				check(r.tool, "again: nothing left to do", repeated.isEmpty(), "${repeated.size} changes repeated")
			}

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
		} finally {
			// 5. Cleanup.
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

	private fun resetDatabase() {
		val tables = dsl.fetch("SELECT tablename FROM pg_tables WHERE schemaname = 'portal' AND tablename <> 'flyway_schema_history'").map { it.get(0, String::class.java) }
		dsl.execute("TRUNCATE " + tables.joinToString { "portal.\"$it\"" } + " CASCADE")
		dsl.execute("INSERT INTO portal.tool_setting (tool) VALUES ('google'), ('github'), ('slack'), ('notion'), ('vaultwarden'), ('documenso') ON CONFLICT DO NOTHING")
	}
}
