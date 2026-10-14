package org.hack4impact.portal.adapters.github

import org.hack4impact.portal.adapters.HttpJson
import org.hack4impact.portal.adapters.NotFound
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.text
import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode

/** A repository in the organization. */
data class GitHubRepo(val id: Long, val name: String, val template: Boolean, val private: Boolean)

/**
 * The organization's repositories, for project repositories (wiki: github-repos): created empty or from a template,
 * or linked, with a team given access. [permission] is GitHub's: pull, push or maintain.
 */
interface GitHubRepoApi {
	fun list(): List<GitHubRepo>
	fun find(name: String): GitHubRepo?
	fun createEmpty(name: String, private: Boolean): GitHubRepo
	fun generate(template: String, name: String, private: Boolean): GitHubRepo
	/** The team's repositories and its permission on each (pull, push, maintain, …), by lowercase name. */
	fun teamAccess(team: String): Map<String, String>
	fun setTeamAccess(team: String, repo: String, permission: String)
	fun removeTeamAccess(team: String, repo: String)
}

/**
 * The GitHub API calls behind [GitHubRepoApi], as the GitHub App. Needs Repository → Administration: read and write,
 * and the installation on all repositories *(unverified until the sandbox check: wiki github-repos, phase 1)*.
 */
class GitHubRepoClient(
	private val baseUrl: String,
	private val org: String,
	private val token: () -> String,
	private val http: HttpJson = HttpJson(Tool.GITHUB),
	private val pageSize: Int = 100,
) : GitHubRepoApi {
	override fun list(): List<GitHubRepo> = pages("/orgs/$org/repos?type=all").map(::repo)

	override fun find(name: String): GitHubRepo? = try {
		repo(http.get("$baseUrl/repos/$org/$name", headers()).body)
	} catch (e: NotFound) {
		null
	}

	override fun createEmpty(name: String, private: Boolean): GitHubRepo = taken(name) {
		// auto_init: a first commit (a README), so the default branch exists and can be cloned at once.
		repo(http.postJson("$baseUrl/orgs/$org/repos", mapOf("name" to name, "private" to private, "auto_init" to true), headers()).body)
	}

	override fun generate(template: String, name: String, private: Boolean): GitHubRepo = taken(name) {
		repo(http.postJson("$baseUrl/repos/$org/$template/generate", mapOf("owner" to org, "name" to name, "private" to private), headers()).body)
	}

	override fun teamAccess(team: String): Map<String, String> =
		pages("/orgs/$org/teams/$team/repos").associate { r -> r.text("name")!!.lowercase() to permission(r) }

	override fun setTeamAccess(team: String, repo: String, permission: String) {
		http.putJson("$baseUrl/orgs/$org/teams/$team/repos/$org/$repo", mapOf("permission" to permission), headers())
	}

	override fun removeTeamAccess(team: String, repo: String) {
		try {
			http.delete("$baseUrl/orgs/$org/teams/$team/repos/$org/$repo", headers())
		} catch (e: NotFound) {
			// The team or the repository is gone, or the team had no access: nothing to take away.
		}
	}

	/** GitHub answers 422 for a name already in use. */
	private fun taken(name: String, create: () -> GitHubRepo): GitHubRepo = try {
		create()
	} catch (e: Rejected) {
		if (e.status == 422) throw Rejected(Tool.GITHUB, "a repository called $name may already exist: link it instead (${e.message})", 422)
		throw e
	}

	private fun repo(r: JsonNode) = GitHubRepo(r.path("id").asLong(), r.text("name")!!, r.path("is_template").asBoolean(false), r.path("private").asBoolean(false))

	/** role_name (read, write, maintain, …) in GitHub's permission words, else the strongest of its permissions. */
	private fun permission(r: JsonNode): String {
		when (r.text("role_name")) {
			"read" -> return "pull"
			"write" -> return "push"
			null -> {}
			else -> return r.text("role_name")!!
		}
		val p = r.path("permissions")
		return listOf("admin", "maintain", "push", "triage", "pull").firstOrNull { p.path(it).asBoolean(false) } ?: "pull"
	}

	private fun pages(path: String): List<JsonNode> {
		val items = mutableListOf<JsonNode>()
		var url: String? = baseUrl + path + (if ('?' in path) "&" else "?") + "per_page=$pageSize"
		while (url != null) {
			val response = http.get(url, headers())
			response.body.forEach { items.add(it) }
			url = response.headers.firstValue("link").orElse(null)
				?.split(",")?.firstOrNull { it.contains("rel=\"next\"") }?.substringAfter('<')?.substringBefore('>')
		}
		return items
	}

	private fun headers() = mapOf(
		"Authorization" to "Bearer ${token()}",
		"Accept" to "application/vnd.github+json",
		"X-GitHub-Api-Version" to "2022-11-28",
	)
}
