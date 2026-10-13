package org.hack4impact.portal.adapters

import org.springframework.stereotype.Component

/** Where to open a resource in its tool, once it exists there (member page, project page). */
@Component
class ToolLinks(private val adapters: AdapterProperties) {
	fun url(tool: String, name: String, externalId: String?): String? {
		externalId ?: return null
		return when (tool) {
			"slack" -> "https://slack.com/app_redirect?channel=$externalId"
			"github" -> adapters.github.org.trim().trim('/').substringAfterLast('/').takeIf { it.isNotEmpty() }?.let { "https://github.com/orgs/$it/teams/$name" }
			"google" -> name.substringBefore('@').let { local -> "https://groups.google.com/a/${name.substringAfter('@', "hack4impact.org")}/g/$local" }
			"notion" -> "https://www.notion.so/${externalId.replace("-", "")}"
			"vaultwarden" -> adapters.vaultwarden.baseUrl.trim().takeIf { it.isNotEmpty() }?.trimEnd('/')
			else -> null
		}
	}
}
