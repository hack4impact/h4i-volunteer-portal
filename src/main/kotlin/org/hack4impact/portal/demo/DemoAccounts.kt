package org.hack4impact.portal.demo

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.AdapterProperties
import org.hack4impact.portal.adapters.google.GoogleAccounts
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

/**
 * Creates the staging demo's six persona accounts (`demo-national@…` to `demo-member@…`) in the sandbox org unit, then
 * exits: `--portal.demo.create-accounts=true` with the sandbox's Google settings (account creation and user scope on).
 * Temporary passwords go to `secrets/demo-accounts.txt` (owner-only, gitignored), never to the log; the addresses go
 * into `deploy/staging/.env` as `PORTAL_DEMO_PERSONAS_*`. Accounts that exist already are left as they are.
 */
@Component
@ConditionalOnBooleanProperty("portal.demo.create-accounts")
class DemoAccounts(
	private val accounts: ObjectProvider<GoogleAccounts>,
	private val adapters: AdapterProperties,
	private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Setting suffix, address, and the persona's name in the demo. */
	private val personas = listOf(
		Triple("NATIONAL", "demo-national", "Nadia Okafor"),
		Triple("LEAD", "demo-lead", "Leo Martinez"),
		Triple("COLEAD", "demo-colead", "Chloe Park"),
		Triple("VIEWER", "demo-viewer", "Victor Hughes"),
		Triple("PROJECTLEAD", "demo-projectlead", "Priya Shah"),
		Triple("MEMBER", "demo-member", "Marcus Chen"),
	)

	override fun run(args: ApplicationArguments) {
		val code = try { create(); 0 } catch (e: IllegalStateException) { log.error("Demo accounts: {}", e.message); 1 } catch (e: AdapterException) { log.error("Demo accounts: {}", e.message); 1 }
		exitProcess(SpringApplication.exit(context, { code }))
	}

	private fun create() {
		val google = accounts.ifAvailable ?: error("needs PORTAL_ADAPTERS_GOOGLE_CREATEACCOUNTS=true")
		val unit = adapters.google.userScope.takeIf { it.isNotBlank() } ?: error("needs PORTAL_ADAPTERS_GOOGLE_USERSCOPE (the sandbox org unit)")
		val domain = "hack4impact.org"
		val lines = mutableListOf("# Staging demo persona accounts. Temporary passwords: Google asks for a new one at first sign-in.")
		val settings = mutableMapOf<String, String>()
		for ((key, local, name) in personas) {
			val email = "$local@$domain"
			val (first, last) = name.split(" ")
			if (google.exists(email)) {
				lines += "$email  (already existed; password unchanged)"
				log.info("{}: already exists", email)
			} else {
				val created = google.createAt(email, first, last, unit, null)
				lines += "$email  ${created.temporaryPassword}"
				log.info("{}: created in {}", email, GoogleAccounts.orgUnitPath(unit))
			}
			settings["PORTAL_DEMO_PERSONAS_$key"] = email
		}
		val secrets = File("secrets").apply { mkdirs() }.resolve("demo-accounts.txt")
		secrets.writeText(lines.joinToString("\n", postfix = "\n"))
		Files.setPosixFilePermissions(secrets.toPath(), PosixFilePermissions.fromString("rw-------"))
		merge(File("deploy/staging/.env"), settings)
		log.info("Demo accounts ready: passwords in {}, addresses in deploy/staging/.env", secrets.path)
	}

	/** Sets [settings] in an env file, keeping its other lines. */
	private fun merge(file: File, settings: Map<String, String>) {
		val kept = if (file.exists()) file.readLines().filter { line -> settings.keys.none { line.startsWith("$it=") } } else emptyList()
		file.writeText((kept + settings.map { (k, v) -> "$k=$v" }).joinToString("\n", postfix = "\n"))
		Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
	}
}
