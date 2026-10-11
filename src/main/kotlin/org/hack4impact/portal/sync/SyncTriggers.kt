package org.hack4impact.portal.sync

import org.hack4impact.portal.resolver.Tool
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import kotlin.system.exitProcess

/**
 * Asks for a sync. Published inside a transaction, so Spring Modulith stores it in the outbox (event_publication)
 * together with whatever data change caused it, and retries it if the app stops before the sync finishes.
 * [trigger] is schedule, release or manual (sync_run.trigger).
 */
data class SyncRequested(val trigger: String, val tools: Set<Tool>? = null)

@Component
class SyncRequests(private val events: ApplicationEventPublisher) {
	@Transactional
	fun request(trigger: String, tools: Set<Tool>? = null) = events.publishEvent(SyncRequested(trigger, tools))
}

/** Runs requested syncs after the requesting transaction commits, in the background. */
@Component
class SyncListener(private val engine: SyncEngine) {
	private val log = LoggerFactory.getLogger(javaClass)

	@ApplicationModuleListener
	fun on(event: SyncRequested) {
		val reports = engine.run(event.trigger, event.tools)
		reports.forEach { log.info("Sync {} ({}): {}", it.tool, event.trigger, it) }
	}
}

/** The nightly sync (PRD: drift detection). Off unless PORTAL_SYNC_CRON is set, e.g. "0 0 3 * * *". */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SyncSchedule(private val requests: SyncRequests) {
	@Scheduled(cron = "\${portal.sync.cron:-}", zone = "America/New_York")
	fun nightly() = requests.request("schedule")
}

/**
 * One dry run, printed, then exit: `./gradlew bootRun -PenvFile=sandbox.env --args='--spring.profiles.active=sync-dry-run'`.
 * Runs directly rather than through the outbox, so the report can be printed.
 */
@Component
@ConditionalOnBooleanProperty("portal.sync.dry-run-once")
class SyncDryRunOnce(private val engine: SyncEngine, private val context: ConfigurableApplicationContext) : ApplicationRunner {
	override fun run(args: ApplicationArguments) {
		val reports = engine.dryRun("manual")
		val text = buildString {
			if (reports.isEmpty()) appendLine("No adapters enabled.")
			reports.forEach { r ->
				appendLine("== ${r.tool}: ${r.status}${r.error?.let { " ($it)" } ?: ""}")
				if (r.error == null) {
					appendLine("   add ${r.adds} · change ${r.changes} · remove ${r.removals} · drift ${r.drift} · unmatched accounts ${r.unmatchedAccounts} · missing resources ${r.missingResources} · create ${r.creates}")
					r.wouldPause?.let { appendLine("   a real run would pause: $it") }
					appendLine("   details: sync_change where run_id = ${r.runId}")
				}
			}
		}
		LoggerFactory.getLogger(javaClass).info("Dry run:\n{}", text)
		exitProcess(SpringApplication.exit(context, { if (reports.any { it.status == "failed" }) 1 else 0 }))
	}
}
