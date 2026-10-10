package org.hack4impact.portal.adapters

import org.hack4impact.portal.adapters.github.GitHubAppToken
import org.hack4impact.portal.adapters.github.GitHubReadAdapter
import org.hack4impact.portal.adapters.google.GoogleReadAdapter
import org.hack4impact.portal.adapters.google.GoogleServiceAccountToken
import org.hack4impact.portal.adapters.slack.SlackReadAdapter
import org.hack4impact.portal.adapters.vaultwarden.VaultwardenApiKeyToken
import org.hack4impact.portal.adapters.vaultwarden.VaultwardenReadAdapter
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
) {
	data class GitHub(
		val enabled: Boolean = false,
		val baseUrl: String = "https://api.github.com",
		val org: String = "",
		val appId: String = "",
		val installationId: String = "",
		val privateKey: String = "",
	)

	data class Slack(val enabled: Boolean = false, val baseUrl: String = "https://slack.com/api", val botToken: String = "")

	data class Google(
		val enabled: Boolean = false,
		val baseUrl: String = "https://admin.googleapis.com",
		val customer: String = "my_customer",
		val orgUnitPath: String = "",
		val adminSubject: String = "",
		val serviceAccountKey: String = "",
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
	)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdapterProperties::class)
class AdapterConfiguration {

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.github.enabled")
	fun gitHubReadAdapter(p: AdapterProperties): ReadAdapter = with(p.github) {
		GitHubReadAdapter(baseUrl, required("github.org", org), GitHubAppToken(baseUrl, required("github.app-id", appId), required("github.installation-id", installationId), inlineOrFile(privateKey)))
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.slack.enabled")
	fun slackReadAdapter(p: AdapterProperties): ReadAdapter = with(p.slack) {
		val token = required("slack.bot-token", botToken)
		SlackReadAdapter(baseUrl, { token })
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.google.enabled")
	fun googleReadAdapter(p: AdapterProperties): ReadAdapter = with(p.google) {
		GoogleReadAdapter(baseUrl, customer, orgUnitPath, GoogleServiceAccountToken(inlineOrFile(serviceAccountKey), required("google.admin-subject", adminSubject)))
	}

	@Bean
	@ConditionalOnBooleanProperty("portal.adapters.vaultwarden.enabled")
	fun vaultwardenReadAdapter(p: AdapterProperties): ReadAdapter = with(p.vaultwarden) {
		val url = required("vaultwarden.base-url", baseUrl).trimEnd('/')
		val http = if (trustedCertificate.isBlank()) HttpJson(Tool.VAULTWARDEN) else HttpJson(Tool.VAULTWARDEN, client = trusting(trustedCertificate))
		VaultwardenReadAdapter(
			url, required("vaultwarden.organization-id", organizationId),
			VaultwardenApiKeyToken(url, required("vaultwarden.client-id", clientId), required("vaultwarden.client-secret", clientSecret), deviceId, http),
			http,
		)
	}

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
