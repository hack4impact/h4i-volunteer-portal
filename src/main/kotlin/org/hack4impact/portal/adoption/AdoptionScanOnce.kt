package org.hack4impact.portal.adoption

import org.hack4impact.portal.db.tables.references.CHAPTER
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.time.LocalDate
import kotlin.system.exitProcess

/**
 * One adoption scan, then exit (profile adoption-scan):
 * `./gradlew bootRun -PenvFile=production-read.env --args='--spring.profiles.active=adoption-scan --portal.adoption.chapters=umd'`
 *
 * Prints counts only. Names and members are personal data: they stay in the local database and are read in the
 * portal's Adoption tab. First, `--portal.adoption.prefixes.<code>=a,b` sets a chapter's extra name prefixes and
 * `--portal.adoption.grandfather-until.<code>=2026-12-20` the date its grandfathered access ends.
 */
@Component
@ConditionalOnBooleanProperty("portal.adoption.scan-once")
class AdoptionScanOnce(
	private val scanner: AdoptionScanner,
	private val reports: AdoptionReports,
	private val dsl: DSLContext,
	private val environment: Environment,
	private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
	override fun run(args: ApplicationArguments) {
		val chapters = environment.getProperty("portal.adoption.chapters", "").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
		val prefixes = Binder.get(environment).bind("portal.adoption.prefixes", Bindable.mapOf(String::class.java, String::class.java)).orElse(emptyMap()).orEmpty()
		prefixes.forEach { (code, list) -> scanner.setPrefixes(code, list.split(',').toSet()) }
		Binder.get(environment).bind("portal.adoption.grandfather-until", Bindable.mapOf(String::class.java, String::class.java)).orElse(emptyMap()).orEmpty()
			.forEach { (code, date) -> scanner.setGrandfatherUntil(code, LocalDate.parse(date)) }

		val scans = scanner.scan(chapters)
		val text = buildString {
			if (scans.isEmpty()) appendLine("No Google, GitHub or Slack adapter enabled.")
			scans.forEach { s ->
				appendLine("== ${s.tool}: ${s.status}${s.error?.let { " ($it)" } ?: ""}")
				if (s.error == null) appendLine("   ${s.resourcesFound} resources · ${s.matched} matched or linked to a chapter · ${s.unmatched} unmatched · members read for ${s.snapshotted} (${s.membersRead} memberships)")
			}
			for (code in chapters.sorted()) {
				val id = dsl.select(CHAPTER.ID).from(CHAPTER).where(CHAPTER.CODE.eq(code)).fetchSingle().value1()!!
				val report = reports.report(id, canEdit = false)
				val r = report.resources
				appendLine("== Adoption report for $code: ${r.size} resources (${r.count { it.state == "matched" }} matched by name, ${r.count { it.state == "linked" }} linked, ${r.count { it.state == "unmanaged" }} unmanaged; ${r.count { it.target == null && it.state != "unmanaged" }} need a lead's decision)")
				appendLine("   expected ${r.sumOf { it.expected }} · grandfathered ${r.sumOf { it.grandfathered }} · unknown accounts ${r.sumOf { it.unknownAccounts }} · would add ${r.sumOf { it.wouldAdd ?: 0 }}")
				appendLine("   grandfathered until: ${report.grandfatheredUntil ?: "not set"}. Details: sign in to the portal, chapter $code, Adoption tab.")
			}
		}
		LoggerFactory.getLogger(javaClass).info("Adoption scan:\n{}", text)
		exitProcess(SpringApplication.exit(context, { if (scans.any { it.status == "failed" }) 1 else 0 }))
	}
}
