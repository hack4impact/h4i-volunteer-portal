package org.hack4impact.portal.projects

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.ToolLinks
import org.hack4impact.portal.adapters.github.GitHubRepoApi
import org.hack4impact.portal.auth.Viewer
import org.hack4impact.portal.db.tables.records.ProjectRecord
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.GITHUB_REPO
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Repositories on a project's GitHub team (wiki: github-repos, decision 147): a lead adds one, created empty or from a
 * template repository, or links an existing one; the sync creates it and gives the team access ([GitHubRepoSync]).
 * Forgetting one takes the team's access away; the repository itself stays in GitHub.
 */
@Component
class ProjectRepos(private val dsl: DSLContext, private val github: ObjectProvider<GitHubRepoApi>, private val links: ToolLinks) {
	private val json = JsonMapper.builder().build()

	companion object {
		const val TOOL = "github"
		/** GitHub's repository names: letters, digits, '.', '-', '_'; at most 100. */
		private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")
		val PERMISSIONS = setOf("pull", "push", "maintain")
		val SOURCES = setOf("empty", "template", "linked")

		/** The team's level on the project, in GitHub's repository permissions. */
		fun permissionFor(access: String?) = when (access) {
			"read" -> "pull"
			"admin" -> "maintain"
			else -> "push"
		}

		fun nameProblem(name: String): String? = when {
			!NAME.matches(name) -> "A repository name uses letters, digits, '.', '-' and '_' only (at most 100)"
			name == "." || name == ".." || name.lowercase().endsWith(".git") -> "GitHub doesn't allow that repository name"
			else -> null
		}

		private fun bad(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
		private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
		private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)
	}

	/** Adds a repository to [resourceId], a GitHub team on [project]. [id]: chosen by a draft, so later changes can refer to it. */
	fun add(chapterId: UUID, project: ProjectRecord, resourceId: UUID, input: RepoInput, viewer: Viewer, id: UUID? = null): UUID {
		val team = dsl.select(RESOURCE.TOOL, RESOURCE.NAME, RESOURCE.ARCHIVED_AT, PROJECT_RESOURCE.ACCESS_LEVEL).from(PROJECT_RESOURCE)
			.join(RESOURCE).on(RESOURCE.ID.eq(PROJECT_RESOURCE.RESOURCE_ID))
			.where(PROJECT_RESOURCE.PROJECT_ID.eq(project.id), PROJECT_RESOURCE.RESOURCE_ID.eq(resourceId))
			.fetchOne() ?: notFound("That team isn't on this project")
		if (team.value1() != TOOL) bad("Repositories go on a GitHub team")
		if (team.value3() != null) conflict("That team was archived")
		val name = input.name.trim()
		nameProblem(name)?.let(::bad)
		if (input.source !in SOURCES) bad("Start from empty, a template, or an existing repository")
		val template = input.template?.trim()?.takeIf { it.isNotEmpty() }
		if (input.source == "template" && template == null) bad("Choose the template repository to start from")
		val code = dsl.select(CHAPTER.CODE).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle().value1()!!
		// A lead links only their chapter's repositories (the naming convention); anything else is national's to link.
		if (input.source == "linked" && !viewer.nationalAdmin && !name.lowercase().startsWith("$code-")) {
			throw ResponseStatusException(HttpStatus.FORBIDDEN, "Leads can link repositories named $code-…; ask national to link $name")
		}
		val permission = input.permission ?: permissionFor(team.value4())
		if (permission !in PERMISSIONS) bad("Team access is read, write or maintain")
		dsl.select(RESOURCE.NAME).from(GITHUB_REPO).join(RESOURCE).on(RESOURCE.ID.eq(GITHUB_REPO.RESOURCE_ID))
			.where(DSL.lower(GITHUB_REPO.NAME).eq(name.lowercase())).fetchOne()
			?.let { conflict("$name is already on the team ${it.value1()}") }
		val repo = dsl.insertInto(GITHUB_REPO).set(GITHUB_REPO.ID, id ?: UUID.randomUUID()).set(GITHUB_REPO.RESOURCE_ID, resourceId).set(GITHUB_REPO.NAME, name)
			.set(GITHUB_REPO.SOURCE, input.source).set(GITHUB_REPO.TEMPLATE, if (input.source == "template") template else null)
			.set(GITHUB_REPO.PRIVATE, input.source != "linked" && input.private).set(GITHUB_REPO.PERMISSION, permission)
			.set(GITHUB_REPO.REQUESTED_BY, viewer.personId)
			.returningResult(GITHUB_REPO.ID).fetchSingle().value1()!!
		touch(project)
		audit(viewer, chapterId, project.id!!, "project.repo_add", "${if (input.source == "linked") "Linked" else "Added"} the repository $name to ${team.value2()}")
		return repo
	}

	/**
	 * Forgets a repository: one never created goes at once; otherwise the sync takes the team's access away and then
	 * forgets it. The repository stays in GitHub (Q45).
	 */
	fun forget(chapterId: UUID, project: ProjectRecord, repoId: UUID, viewer: Viewer) {
		val repo = onProject(project, repoId)
		if (repo.state == "pending" || repo.state == "failed") dsl.deleteFrom(GITHUB_REPO).where(GITHUB_REPO.ID.eq(repoId)).execute()
		else dsl.update(GITHUB_REPO).set(GITHUB_REPO.STATE, "forgetting").where(GITHUB_REPO.ID.eq(repoId)).execute()
		touch(project)
		audit(viewer, chapterId, project.id!!, "project.repo_forget", "Removed the repository ${repo.name} from the team (it stays in GitHub)")
	}

	/** A repository that failed, tried again at the next sync with a fresh count. */
	fun retry(chapterId: UUID, project: ProjectRecord, repoId: UUID, viewer: Viewer) {
		val repo = onProject(project, repoId)
		if (repo.state != "failed") conflict("${repo.name} hasn't failed")
		dsl.update(GITHUB_REPO).set(GITHUB_REPO.STATE, "pending").set(GITHUB_REPO.ATTEMPTS, 0).setNull(GITHUB_REPO.RETRY_AT).setNull(GITHUB_REPO.LAST_ERROR)
			.where(GITHUB_REPO.ID.eq(repoId)).execute()
		audit(viewer, chapterId, project.id!!, "project.repo_retry", "Asked to try ${repo.name} again")
	}

	fun name(repoId: UUID): String? = dsl.select(GITHUB_REPO.NAME).from(GITHUB_REPO).where(GITHUB_REPO.ID.eq(repoId)).fetchOne()?.value1()

	/** The repositories of these GitHub teams, for the project page. */
	fun rows(resourceIds: Collection<UUID>): Map<UUID, List<RepoRow>> =
		if (resourceIds.isEmpty()) emptyMap() else dsl.selectFrom(GITHUB_REPO).where(GITHUB_REPO.RESOURCE_ID.`in`(resourceIds)).orderBy(GITHUB_REPO.NAME).fetch()
			.groupBy({ it.resourceId!! }, {
				RepoRow(it.id!!, it.name!!, it.source!!, it.template, it.private!!, it.permission!!, it.state!!, it.lastError,
					if (it.githubId != null) links.githubRepo(it.name!!) else null)
			})

	/** Templates and repositories to link, live from GitHub; [available] false when GitHub isn't connected or can't be read. */
	fun options(chapterId: UUID, viewer: Viewer): GitHubRepoOptions {
		val code = dsl.select(CHAPTER.CODE).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle().value1()!!
		val api = github.ifAvailable ?: return GitHubRepoOptions(false, emptyList(), emptyList(), viewer.nationalAdmin, "$code-")
		val all = try {
			api.list()
		} catch (e: AdapterException) {
			return GitHubRepoOptions(false, emptyList(), emptyList(), viewer.nationalAdmin, "$code-")
		}
		val linked = dsl.select(GITHUB_REPO.NAME, RESOURCE.NAME).from(GITHUB_REPO).join(RESOURCE).on(RESOURCE.ID.eq(GITHUB_REPO.RESOURCE_ID)).fetch()
			.associate { it.value1()!!.lowercase() to it.value2()!! }
		return GitHubRepoOptions(
			true,
			all.filter { it.template }.map { it.name }.sorted(),
			all.filter { viewer.nationalAdmin || it.name.lowercase().startsWith("$code-") }.map { RepoOption(it.name, linked[it.name.lowercase()]) }.sortedBy { it.name.lowercase() },
			viewer.nationalAdmin, "$code-",
		)
	}

	/** The repository, on a GitHub team this project has. */
	private fun onProject(project: ProjectRecord, repoId: UUID) =
		dsl.select(GITHUB_REPO.asterisk()).from(GITHUB_REPO).join(PROJECT_RESOURCE).on(PROJECT_RESOURCE.RESOURCE_ID.eq(GITHUB_REPO.RESOURCE_ID))
			.where(GITHUB_REPO.ID.eq(repoId), PROJECT_RESOURCE.PROJECT_ID.eq(project.id)).fetchOne()?.into(GITHUB_REPO)
			?: notFound("No such repository on this project")

	private fun touch(project: ProjectRecord) = dsl.update(PROJECT).set(PROJECT.UPDATED_AT, OffsetDateTime.now()).where(PROJECT.ID.eq(project.id)).execute()

	private fun audit(viewer: Viewer, chapterId: UUID, projectId: UUID, action: String, summary: String) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (viewer.personId != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, viewer.personId)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, "project").set(AUDIT_EVENT.TARGET_ID, projectId)
			.set(AUDIT_EVENT.CHAPTER_ID, chapterId).set(AUDIT_EVENT.AFTER, JSONB.valueOf(json.writeValueAsString(mapOf("summary" to summary))))
			.execute()
	}
}
