package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdapterWiringTests {
	private val runner = ApplicationContextRunner().withUserConfiguration(AdapterConfiguration::class.java)

	/** Sets properties the way the vault delivers them: as environment variables. */
	private fun env(vararg vars: Pair<String, String>) = runner.withInitializer { ctx ->
		ctx.environment.propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", vars.toMap()))
	}

	@Test
	fun `no adapter is created unless enabled`() {
		runner.run { assertTrue(it.getBeansOfType(ReadAdapter::class.java).isEmpty()) }
	}

	@Test
	fun `every adapter is created from environment variables with the documented names`() {
		val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded
		val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(key) + "\n-----END PRIVATE KEY-----\n"
		val googleKey = """{"type":"service_account","client_email":"portal@h4i-portal.iam.gserviceaccount.com","client_id":"1","private_key_id":"k","private_key":${HttpJson.MAPPER.writeValueAsString(pem)},"token_uri":"https://oauth2.googleapis.com/token"}"""
		env(
			"PORTAL_ADAPTERS_GITHUB_ENABLED" to "true", "PORTAL_ADAPTERS_GITHUB_ORG" to "h4i-portal-sandbox",
			"PORTAL_ADAPTERS_GITHUB_APPID" to "123", "PORTAL_ADAPTERS_GITHUB_INSTALLATIONID" to "456", "PORTAL_ADAPTERS_GITHUB_PRIVATEKEY" to pem,
			"PORTAL_ADAPTERS_SLACK_ENABLED" to "true", "PORTAL_ADAPTERS_SLACK_BOTTOKEN" to "xoxb-test",
			"PORTAL_ADAPTERS_GOOGLE_ENABLED" to "true", "PORTAL_ADAPTERS_GOOGLE_ADMINSUBJECT" to "provisioning@hack4impact.org",
			"PORTAL_ADAPTERS_GOOGLE_ORGUNITPATH" to "/Portal Sandbox", "PORTAL_ADAPTERS_GOOGLE_SERVICEACCOUNTKEY" to googleKey,
			"PORTAL_ADAPTERS_VAULTWARDEN_ENABLED" to "true", "PORTAL_ADAPTERS_VAULTWARDEN_BASEURL" to "https://vault-staging.example.org",
			"PORTAL_ADAPTERS_VAULTWARDEN_ORGANIZATIONID" to "org-1", "PORTAL_ADAPTERS_VAULTWARDEN_CLIENTID" to "user.abc",
			"PORTAL_ADAPTERS_VAULTWARDEN_CLIENTSECRET" to "secret",
		).run { ctx ->
			assertEquals(setOf(Tool.GITHUB, Tool.SLACK, Tool.GOOGLE, Tool.VAULTWARDEN), ctx.getBeansOfType(ReadAdapter::class.java).values.map { it.tool }.toSet())
			assertEquals("/Portal Sandbox", ctx.getBean(AdapterProperties::class.java).google.orgUnitPath)
		}
	}

	@Test
	fun `Vaultwarden can trust a staging CA certificate`() {
		env(
			"PORTAL_ADAPTERS_VAULTWARDEN_ENABLED" to "true", "PORTAL_ADAPTERS_VAULTWARDEN_BASEURL" to "https://localhost:8443",
			"PORTAL_ADAPTERS_VAULTWARDEN_ORGANIZATIONID" to "org-1", "PORTAL_ADAPTERS_VAULTWARDEN_CLIENTID" to "user.abc",
			"PORTAL_ADAPTERS_VAULTWARDEN_CLIENTSECRET" to "secret",
			"PORTAL_ADAPTERS_VAULTWARDEN_TRUSTEDCERTIFICATE" to "src/test/resources/tls/test-ca.crt",
		).run { ctx -> assertEquals(Tool.VAULTWARDEN, ctx.getBean(ReadAdapter::class.java).tool) }
	}

	@Test
	fun `an enabled adapter without credentials fails at startup, naming the missing setting`() {
		env("PORTAL_ADAPTERS_SLACK_ENABLED" to "true").run { ctx ->
			assertContains(ctx.startupFailure.toString(), "portal.adapters.slack.bot-token must be set")
		}
	}

	@Test
	fun `the check reports what each tool has, and fails when a tool can't be read`() {
		val slack = InMemoryReadAdapter(
			Tool.SLACK,
			listOf(ToolAccount("U1", "ada", null, null, AccountState.ACTIVE), ToolAccount("U2", "alan", null, null, AccountState.SUSPENDED)),
			mapOf(ToolResource("C1", "umd-rise-dc") to listOf(ResourceMember("U1", Access.WRITE))),
		)
		val ok = AdapterCheck.run(listOf(slack))
		assertTrue(ok.ok)
		assertContains(ok.report, "accounts: 2 (active 1, suspended 1)")
		assertContains(ok.report, "umd-rise-dc: 1 members")

		val github = InMemoryReadAdapter(Tool.GITHUB, failure = AuthFailed(Tool.GITHUB, "HTTP 401"))
		val failed = AdapterCheck.run(listOf(slack, github))
		assertFalse(failed.ok)
		assertContains(failed.report, "FAILED: GITHUB: HTTP 401")
		assertFalse(AdapterCheck.run(emptyList()).ok)
	}
}
