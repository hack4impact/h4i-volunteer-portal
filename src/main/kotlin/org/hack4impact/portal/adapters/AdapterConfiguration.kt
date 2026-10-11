package org.hack4impact.portal.adapters

import org.hack4impact.portal.adapters.github.GitHubAppToken
import org.hack4impact.portal.adapters.github.GitHubReadAdapter
import org.hack4impact.portal.adapters.github.GitHubWriteAdapter
import org.hack4impact.portal.adapters.google.GoogleReadAdapter
import org.hack4impact.portal.adapters.google.GoogleWriteAdapter
import org.hack4impact.portal.adapters.google.GoogleServiceAccountToken
import org.hack4impact.portal.adapters.slack.SlackReadAdapter
import org.hack4impact.portal.adapters.slack.SlackWriteAdapter
import org.hack4impact.portal.adapters.vaultwarden.VaultwardenApiKeyToken
import org.hack4impact.portal.adapters.vaultwarden.VaultwardenReadAdapter
import org.hack4impact.portal.adapters.vaultwarden.VaultwardenWriteAdapter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.hack4impact.portal.notion.NotionClient
import org.hack4impact.portal.resolver.Tool
import java.io.File
import java.net.http.HttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Tool credentials. As environment variables (from the vault, wiki decision 30) the names drop the dashes, e.g.
 * `PORTAL_ADAPTERS_GITHUB_ENABLED`, `PORTAL_ADAPTERS_GITHUB_APPID`, `PORTAL_ADAPTERS_SLACK_BOTTOKEN`.
 * Keys (GitHub PEM, Google JSON) can be given inline or as a file path.
 */
@ConfigurationProperties("portal.adapters")
data class AdapterProperties(
	val github: GitHub = GitHub(),
	val slack: Slack = Slack(),
	val google: Google = Google(),
	val vaultwarden: Vaultwarden = Vaultwarden(),
	val notion: Notion = Notion(),
) {
	data class GitHub(
		val enabled: Boolean = false,
		val baseUrl: String = "https://api.github.com",
		/** The national organization (wiki decision 78); chapter organizations are imported later. */
		val org: String = "",
		val appId: String = "",
		val installationId: String = "",
		val privateKey: String = "",
		/** Also change GitHub (step 9); the App then needs Members: Read and write. */
		val write: Boolean = false,
	)

	/** [write]: also change Slack (step 9). Off unless set, so a read-only env file can never change a tool. */
	data class Slack(val enabled: Boolean = false, val baseUrl: String = "https://slack.com/api", val botToken: String = "", val write: Boolean = false)

	data class Google(
		val enabled: Boolean = false,
		val baseUrl: String = "https://admin.googleapis.com",
		val customer: String = "my_customer",
		val orgUnitPath: String = "",
		val adminSubject: String = "",
		val serviceAccountKey: String = "",
		/** Regex: only matching groups (email or name) are read or, from step 9, written. Empty = every group (production). */
		val groupScope: String = "",
		/** Also change Google groups (step 9); domain-wide delegation must then also allow the group write scope. */
		val write: Boolean = false,
	)

	/** An internal Notion integration's secret, shared with the chapters' parent pages (Notion routes, step 8). */
	data class Notion(
		val enabled: Boolean = false,
		val baseUrl: String = "https://api.notion.com/v1",
		val token: String = "",
		/** Also create and trash project pages (step 9); the integration then needs "Insert content" and "Update content". */
		val write: Boolean = false,
	)

	data class Vaultwarden(
		val enabled: Boolean = false,
		val baseUrl: String = "",
		val organizationId: String = "",
		val clientId: String = "",
		val clientSecret: String = "",
		val deviceId: String = "h4i-portal",
		/** Staging only: a CA certificate (PEM path) to trust instead of the JDK's, e.g. the localhost-only staging CA. */
		val trustedCertificate: String = "",
		/** Also change Vaultwarden (step 9): needs the service admin's [masterPassword] to unlock the organization key. */
		val write: Boolean = false,
		val masterPassword: String = "",
	)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdapterProperties::class)
class AdapterConfiguration {

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.github.enabled")
	fun gitHubReadAdapter(p: AdapterProperties): ReadAdapter = with(p.github) {
		GitHubReadAdapter(
			baseUrl, githubOrg(required("github.org", org)),
			GitHubAppToken(baseUrl, required("github.app-id", appId), required("github.installation-id", installationId), inlineOrFile(privateKey)),
			http = patient(Tool.GITHUB),
		)
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.slack.enabled")
	fun slackReadAdapter(p: AdapterProperties): ReadAdapter = with(p.slack) {
		val token = required("slack.bot-token", botToken)
		SlackReadAdapter(baseUrl, { token }, http = patient(Tool.SLACK))
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.github.write")
	fun gitHubWriteAdapter(p: AdapterProperties): WriteAdapter = with(p.github) {
		if (!enabled) error("portal.adapters.github.write needs portal.adapters.github.enabled")
		val token = GitHubAppToken(baseUrl, required("github.app-id", appId), required("github.installation-id", installationId), inlineOrFile(privateKey))
		GitHubWriteAdapter(baseUrl, githubOrg(required("github.org", org)), token, http = patient(Tool.GITHUB))
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.google.write")
	fun googleWriteAdapter(p: AdapterProperties): WriteAdapter = with(p.google) {
		if (!enabled) error("portal.adapters.google.write needs portal.adapters.google.enabled")
		GoogleWriteAdapter(
			baseUrl,
			GoogleServiceAccountToken(inlineOrFile(serviceAccountKey), required("google.admin-subject", adminSubject), GoogleServiceAccountToken.WRITE_SCOPES),
			groupScope = groupScope.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
			http = patient(Tool.GOOGLE),
		)
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.vaultwarden.write")
	fun vaultwardenWriteAdapter(p: AdapterProperties): WriteAdapter = with(p.vaultwarden) {
		if (!enabled) error("portal.adapters.vaultwarden.write needs portal.adapters.vaultwarden.enabled")
		val url = required("vaultwarden.base-url", baseUrl).trimEnd('/')
		val http = if (trustedCertificate.isBlank()) patient(Tool.VAULTWARDEN) else HttpJson(Tool.VAULTWARDEN, client = trusting(trustedCertificate), rateLimits = RateLimitPolicy.PATIENT)
		VaultwardenWriteAdapter(
			url, required("vaultwarden.organization-id", organizationId),
			VaultwardenApiKeyToken(url, required("vaultwarden.client-id", clientId), required("vaultwarden.client-secret", clientSecret), deviceId, http),
			required("vaultwarden.master-password", masterPassword), http,
		)
	}

	/** Writes need the tool enabled and its own switch (`…_WRITE=true`); the tool's dry-run setting still applies on top. */
	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.slack.write")
	fun slackWriteAdapter(p: AdapterProperties): WriteAdapter = with(p.slack) {
		if (!enabled) error("portal.adapters.slack.write needs portal.adapters.slack.enabled")
		val token = required("slack.bot-token", botToken)
		SlackWriteAdapter(baseUrl, { token }, http = patient(Tool.SLACK))
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.google.enabled")
	fun googleReadAdapter(p: AdapterProperties): ReadAdapter = with(p.google) {
		GoogleReadAdapter(
			baseUrl, customer, orgUnitPath, GoogleServiceAccountToken(inlineOrFile(serviceAccountKey), required("google.admin-subject", adminSubject)),
			groupScope = groupScope.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) },
			http = patient(Tool.GOOGLE),
		)
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.vaultwarden.enabled")
	fun vaultwardenReadAdapter(p: AdapterProperties): ReadAdapter = with(p.vaultwarden) {
		val url = required("vaultwarden.base-url", baseUrl).trimEnd('/')
		val http = if (trustedCertificate.isBlank()) patient(Tool.VAULTWARDEN) else HttpJson(Tool.VAULTWARDEN, client = trusting(trustedCertificate), rateLimits = RateLimitPolicy.PATIENT)
		VaultwardenReadAdapter(
			url, required("vaultwarden.organization-id", organizationId),
			VaultwardenApiKeyToken(url, required("vaultwarden.client-id", clientId), required("vaultwarden.client-secret", clientSecret), deviceId, http),
			http,
		)
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.notion.enabled")
	fun notionClient(p: AdapterProperties) = NotionClient(p.notion.baseUrl.trimEnd('/'), required("notion.token", p.notion.token))

	/** Real adapters wait out rate limits: scans and syncs run in the background and would otherwise fail on large workspaces. */
	private fun patient(tool: Tool) = HttpJson(tool, rateLimits = RateLimitPolicy.PATIENT)

	/** An HTTP client that trusts only the CA certificate at [pemPath]. */
	private fun trusting(pemPath: String): HttpClient {
		val certificate = File(pemPath).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
		val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
			load(null)
			setCertificateEntry("trusted", certificate)
		}
		val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
		val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).sslContext(tls).build()
	}

	private fun required(name: String, value: String) = value.ifBlank { error("portal.adapters.$name must be set when the adapter is enabled") }

	/** A key given inline (PEM or JSON) or as a path to a file holding it. */
	private fun inlineOrFile(value: String): String {
		require(value.isNotBlank()) { "A key is required when the adapter is enabled" }
		val trimmed = value.trim()
		return if (trimmed.startsWith("-----") || trimmed.startsWith("{")) trimmed else File(trimmed).readText()
	}
}

/** The organization's login, also when given as its URL or with a trailing slash ("hack4impact/", "https://github.com/hack4impact"). */
internal fun githubOrg(value: String): String =
	value.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/").trim('/').substringBefore('/')
