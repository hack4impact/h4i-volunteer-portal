package org.hack4impact.portal.adapters.slack

import org.hack4impact.portal.adapters.AccountState
import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.AuthFailed
import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.RateLimited
import org.hack4impact.portal.adapters.ReadAdapter
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.ResourceMember
import org.hack4impact.portal.adapters.ToolAccount
import org.hack4impact.portal.adapters.ToolResource
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Access
import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * Reads one Slack workspace with a bot token: users (deactivated ones as SUSPENDED, bots left out), public and
 * private channels the bot can see (archived included, for adoption), and channel members. Slack has no
 * per-member channel roles in its API, so every member is WRITE.
 */
class SlackReadAdapter(
	private val baseUrl: String,
	private val token: () -> String,
	private val http: HttpJson = HttpJson(Tool.SLACK),
	private val pageSize: Int = 200,
) : ReadAdapter {
	override val tool = Tool.SLACK

	override fun accounts(): List<ToolAccount> =
		pages("users.list", "members")
			.filterNot { it.path("is_bot").asBoolean() || it.text("id") == "USLACKBOT" }
			.map {
				val profile = it.path("profile")
				ToolAccount(
					it.text("id"), it.text("name"), profile.text("email"), profile.text("real_name"),
					if (it.path("deleted").asBoolean()) AccountState.SUSPENDED else AccountState.ACTIVE,
				)
			}

	override fun resources(): List<ToolResource> =
		pages("conversations.list?types=public_channel,private_channel&exclude_archived=false", "channels")
			.map { ToolResource(it.text("id")!!, it.text("name")!!, it.path("is_archived").asBoolean()) }

	override fun members(resourceId: String): List<ResourceMember> =
		pages("conversations.members?channel=${HttpJson.encode(resourceId)}", "members")
			.map { ResourceMember(it.asString(), Access.WRITE) }

	/** Follows Slack's cursor pagination; Slack reports most errors as HTTP 200 with ok=false. */
	private fun pages(method: String, field: String): List<JsonNode> {
		val items = mutableListOf<JsonNode>()
		var cursor = ""
		do {
			val url = "$baseUrl/$method${if ('?' in method) "&" else "?"}limit=$pageSize" + (if (cursor.isEmpty()) "" else "&cursor=${HttpJson.encode(cursor)}")
			val body = http.get(url, mapOf("Authorization" to "Bearer ${token()}")).body
			if (!body.path("ok").asBoolean()) throw slackError(tool, method, body.text("error") ?: "unknown_error")
			body.path(field).forEach { items.add(it) }
			cursor = body.path("response_metadata").text("next_cursor").orEmpty()
		} while (cursor.isNotEmpty())
		return items
	}

}

/** Slack answers most errors with HTTP 200 and ok=false; this maps its codes onto the adapters' errors. */
internal fun slackError(tool: Tool, method: String, code: String): AdapterException = when (code) {
	"invalid_auth", "not_authed", "account_inactive", "token_revoked", "token_expired", "missing_scope", "no_permission" ->
		AuthFailed(tool, "$method: $code")
	"ratelimited" -> RateLimited(tool, Duration.ofSeconds(60))
	"channel_not_found" -> NotFound(tool, method)
	"name_taken" -> Rejected(tool, "$method: name_taken (probably an existing channel the portal can't see, often a private one: invite the portal's Slack app to it and adopt it)")
	else -> Rejected(tool, "$method: $code")
}
