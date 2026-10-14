package org.hack4impact.portal.projects

import org.hack4impact.portal.adapters.AdapterException
import org.hack4impact.portal.adapters.AuthFailed
import org.hack4impact.portal.adapters.Rejected
import org.hack4impact.portal.adapters.WriteAdapter
import org.hack4impact.portal.adapters.github.GitHubRepoApi
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.GITHUB_REPO
import org.hack4impact.portal.db.tables.references.PROJECT
import org.hack4impact.portal.db.tables.references.PROJECT_RESOURCE
import org.hack4impact.portal.db.tables.references.RESOURCE
import org.hack4impact.portal.resolver.Tool
import org.hack4impact.portal.sync.SyncApplier
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The GitHub step after the team step (wiki: github-repos): creates or links the repositories leads added, once their
 * team exists, and gives the team its access; puts that access back if someone changed or removed it by hand (each
 * time on the project's activity); takes it away from forgotten ones. Runs only when GitHub is out of dry run with
 * its write switch on. Failures back off like other changes; after five, or when GitHub refuses the name, the
 * repository shows as failed until a lead tries again.
 */
@Component
class GitHubRepoSync(private val dsl: DSLContext, private val github: ObjectProvider<GitHubRepoApi>, private val writers: List<WriteAdapter>) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val json = JsonMapper.builder().build()

	data class Report(val created: Int = 0, val linked: Int = 0, val restored: Int = 0, val forgotten: Int = 0, val failed: Int = 0)

	fun canWrite() = github.ifAvailable != null && writers.any { it.tool == Tool.GITHUB }

	fun sync(now: OffsetDateTime = OffsetDateTime.now()): Report {
		val api = github.ifAvailable ?: return Report()
		val rows = dsl.select(GITHUB_REPO.asterisk(), RESOURCE.EXTERNAL_ID, RESOURCE.NAME, RESOURCE.ARCHIVED_AT).from(GITHUB_REPO)
			.join(RESOURCE).on(RESOURCE.ID.eq(GITHUB_REPO.RESOURCE_ID)).orderBy(GITHUB_REPO.CREATED_AT, GITHUB_REPO.NAME).fetch()
		var report = Report()
		val access = mutableMapOf<String, Map<String, String>>() // team → its repositories' permissions, read once
		try {
			for (r in rows) {
				val repo = r.into(GITHUB_REPO)
				val team = r.get(RESOURCE.EXTERNAL_ID) ?: continue // the team isn't created yet
				if (r.get(RESOURCE.ARCHIVED_AT) != null) continue // a closed project's team: the repository is left as it is
				val teamName = r.get(RESOURCE.NAME)!!
				when (repo.state) {
					"pending" -> {
						if (repo.retryAt?.isAfter(now) == true) continue
						report = try {
							// Created on an earlier try whose team access then failed: only the access is left to give.
							val made = when {
								repo.githubId != null || repo.source == "linked" ->
									api.find(repo.name!!) ?: throw Rejected(Tool.GITHUB, "no repository called ${repo.name} in the organization")
								repo.source == "template" -> api.generate(repo.template!!, repo.name!!, repo.private!!)
								else -> api.createEmpty(repo.name!!, repo.private!!)
							}
							dsl.update(GITHUB_REPO).set(GITHUB_REPO.GITHUB_ID, made.id).where(GITHUB_REPO.ID.eq(repo.id)).execute()
							api.setTeamAccess(team, made.name, repo.permission!!)
							dsl.update(GITHUB_REPO).set(GITHUB_REPO.STATE, "ready").set(GITHUB_REPO.GITHUB_ID, made.id).set(GITHUB_REPO.ATTEMPTS, 0)
								.setNull(GITHUB_REPO.RETRY_AT).setNull(GITHUB_REPO.LAST_ERROR).where(GITHUB_REPO.ID.eq(repo.id)).execute()
							val verb = if (repo.source == "linked") "Linked" else "Created"
							note(repo.resourceId!!, "$verb the repository ${made.name} on GitHub and gave $teamName ${words(repo.permission!!)} access")
							if (repo.source == "linked") report.copy(linked = report.linked + 1) else report.copy(created = report.created + 1)
						} catch (e: AuthFailed) {
							throw e
						} catch (e: AdapterException) {
							failed(repo.id!!, repo.attempts!!, e, now)
							report.copy(failed = report.failed + 1)
						}
					}
					"ready" -> {
						val has = access.getOrPut(team) { api.teamAccess(team) }[repo.name!!.lowercase()]
						if (has == repo.permission) continue
						api.setTeamAccess(team, repo.name!!, repo.permission!!)
						note(repo.resourceId!!, "Gave $teamName ${words(repo.permission!!)} access to ${repo.name} again (it had ${has?.let { "${words(it)} access" } ?: "none"}, changed in GitHub)")
						report = report.copy(restored = report.restored + 1)
					}
					"forgetting" -> {
						api.removeTeamAccess(team, repo.name!!)
						dsl.deleteFrom(GITHUB_REPO).where(GITHUB_REPO.ID.eq(repo.id)).execute()
						note(repo.resourceId!!, "Took $teamName's access to ${repo.name} away; the repository stays in GitHub")
						report = report.copy(forgotten = report.forgotten + 1)
					}
				}
			}
		} catch (e: AuthFailed) {
			log.warn("GitHub repositories: stopped, {}", e.message)
		} catch (e: AdapterException) {
			log.warn("GitHub repositories: {}", e.message)
		}
		return report
	}

	/** Backs off like other changes (1 min … 2 h); after the last try, or when GitHub refuses it outright, it waits for a lead. */
	private fun failed(id: UUID, attempts: Int, e: AdapterException, now: OffsetDateTime) {
		val tries = attempts + 1
		val giveUp = tries >= SyncApplier.MAX_ATTEMPTS || (e is Rejected && e.status == 422)
		dsl.update(GITHUB_REPO).set(GITHUB_REPO.ATTEMPTS, tries).set(GITHUB_REPO.LAST_ERROR, e.message?.take(500))
			.set(GITHUB_REPO.STATE, if (giveUp) "failed" else "pending").set(GITHUB_REPO.RETRY_AT, if (giveUp) null else now.plus(SyncApplier.backoff(tries)))
			.where(GITHUB_REPO.ID.eq(id)).execute()
	}

	private fun words(permission: String) = when (permission) {
		"pull" -> "read"
		"push" -> "write"
		else -> permission
	}

	/** On the activity of every project with this team. */
	private fun note(resourceId: UUID, summary: String) {
		val projects = dsl.select(PROJECT.ID, PROJECT.CHAPTER_ID).from(PROJECT_RESOURCE).join(PROJECT).on(PROJECT.ID.eq(PROJECT_RESOURCE.PROJECT_ID))
			.where(PROJECT_RESOURCE.RESOURCE_ID.eq(resourceId)).fetch()
		for (p in projects) {
			dsl.insertInto(AUDIT_EVENT).set(AUDIT_EVENT.ACTOR_TYPE, "sync").set(AUDIT_EVENT.ACTION, "project.repo_sync")
				.set(AUDIT_EVENT.TARGET_TYPE, "project").set(AUDIT_EVENT.TARGET_ID, p.value1()).set(AUDIT_EVENT.CHAPTER_ID, p.value2())
				.set(AUDIT_EVENT.AFTER, JSONB.valueOf(json.writeValueAsString(mapOf("summary" to summary)))).execute()
		}
	}
}
