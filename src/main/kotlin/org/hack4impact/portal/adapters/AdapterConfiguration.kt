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
import java.io.File

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
		VaultwardenReadAdapter(url, required("vaultwarden.organization-id", organizationId), VaultwardenApiKeyToken(url, required("vaultwarden.client-id", clientId), required("vaultwarden.client-secret", clientSecret), deviceId))
	}

	private fun required(name: String, value: String) = value.ifBlank { error("portal.adapters.$name must be set when the adapter is enabled") }

	/** A key given inline (PEM or JSON) or as a path to a file holding it. */
	private fun inlineOrFile(value: String): String {
		require(value.isNotBlank()) { "A key is required when the adapter is enabled" }
		val trimmed = value.trim()
		return if (trimmed.startsWith("-----") || trimmed.startsWith("{")) trimmed else File(trimmed).readText()
	}
}
