package org.hack4impact.portal.adapters.google

import com.google.auth.oauth2.ServiceAccountCredentials
import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode

/**
 * Reads Google Workspace through the Directory API: users (optionally limited to one org unit, e.g. the
 * sandbox OU), groups, and group members. Owners and managers count as ADMIN; nested groups are left out.
 */
class GoogleReadAdapter(
	private val baseUrl: String,
	private val customer: String,
	private val orgUnitPath: String?,
	private val token: () -> String,
	private val http: HttpJson = HttpJson(Tool.GOOGLE),
	private val pageSize: Int = 200,
) : ReadAdapter {
	override val tool = Tool.GOOGLE
	private val directory get() = "$baseUrl/admin/directory/v1"

	override fun accounts(): List<ToolAccount> {
		val ou = orgUnitPath?.takeIf { it.isNotBlank() }?.let { "&query=" + HttpJson.encode("orgUnitPath='$it'") } ?: ""
		return pages("$directory/users?customer=${HttpJson.encode(customer)}$ou", "users").map {
			ToolAccount(
				it.text("id"), it.text("primaryEmail"), it.text("primaryEmail"), it.path("name").text("fullName"),
				if (it.path("suspended").asBoolean()) AccountState.SUSPENDED else AccountState.ACTIVE,
			)
		}
	}

	override fun resources(): List<ToolResource> =
		pages("$directory/groups?customer=${HttpJson.encode(customer)}", "groups").map { ToolResource(it.text("email")!!, it.text("name") ?: it.text("email")!!) }

	override fun members(resourceId: String): List<ResourceMember> =
		pages("$directory/groups/${HttpJson.encode(resourceId)}/members", "members")
			.filter { it.text("type") == "USER" }
			.map { ResourceMember(it.text("id")!!, if (it.text("role") in setOf("OWNER", "MANAGER")) Access.ADMIN else Access.WRITE) }

	private fun pages(url: String, field: String): List<JsonNode> {
		val items = mutableListOf<JsonNode>()
		var pageToken = ""
		do {
			val body = http.get(
				"$url${if ('?' in url) "&" else "?"}maxResults=$pageSize" + (if (pageToken.isEmpty()) "" else "&pageToken=${HttpJson.encode(pageToken)}"),
				mapOf("Authorization" to "Bearer ${token()}"),
			).body
			body.path(field).forEach { items.add(it) }
			pageToken = body.text("nextPageToken").orEmpty()
		} while (pageToken.isNotEmpty())
		return items
	}
}

/**
 * Access tokens for a service account with domain-wide delegation, acting as [subject] (the limited
 * provisioning admin). Read-only scopes until step 9 adds writes.
 */
class GoogleServiceAccountToken(keyJson: String, subject: String, scopes: List<String> = READ_SCOPES) : () -> String {
	private val credentials = ServiceAccountCredentials.fromStream(keyJson.byteInputStream()).createScoped(scopes).createDelegated(subject)

	@Synchronized
	override fun invoke(): String {
		credentials.refreshIfExpired()
		return checkNotNull(credentials.accessToken) { "Google returned no access token" }.tokenValue
	}

	companion object {
		val READ_SCOPES = listOf(
			"https://www.googleapis.com/auth/admin.directory.user.readonly",
			"https://www.googleapis.com/auth/admin.directory.group.readonly",
			"https://www.googleapis.com/auth/admin.directory.group.member.readonly",
		)
	}
}
