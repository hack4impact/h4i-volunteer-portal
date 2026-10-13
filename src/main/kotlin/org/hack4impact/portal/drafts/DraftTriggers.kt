package org.hack4impact.portal.drafts

import org.hack4impact.portal.db.tables.references.CHANGESET
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.mail.Emails
import org.hack4impact.portal.mail.Mailer
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** The release summaries (PRD: members get one notification summarizing their new projects and access), after commit. */
@Component
class DraftEmails(private val dsl: DSLContext, private val mailer: Mailer, @Value("\${portal.base-url}") private val baseUrl: String) {
	@ApplicationModuleListener
	fun on(event: DraftReleased) {
		val code = dsl.select(CHAPTER.CODE).from(CHANGESET).join(CHAPTER).on(CHAPTER.ID.eq(CHANGESET.CHAPTER_ID)).where(CHANGESET.ID.eq(event.changesetId)).fetchOne()?.value1()
		for (p in event.people) {
			val person = dsl.selectFrom(PERSON).where(PERSON.ID.eq(p.personId)).fetchOne() ?: continue
			val to = person.orgEmail ?: person.schoolEmail ?: person.personalEmail ?: continue
			val first = person.preferredName?.takeIf { it.isNotBlank() } ?: person.firstName!!
			mailer.send(
				Emails.releaseSummary(first, event.chapter, event.draft, p.joins, p.leaves, p.gains, p.loses, p.afterAgreement, "${baseUrl.trimEnd('/')}/chapters/$code/projects"),
				to, person = p.personId,
			)
		}
	}

	@ApplicationModuleListener
	fun on(event: ScheduledReleaseFailed) {
		val person = dsl.selectFrom(PERSON).where(PERSON.ID.eq(event.scheduledBy)).fetchOne() ?: return
		val to = person.orgEmail ?: return
		val code = dsl.select(CHAPTER.CODE).from(CHANGESET).join(CHAPTER).on(CHAPTER.ID.eq(CHANGESET.CHAPTER_ID)).where(CHANGESET.ID.eq(event.changesetId)).fetchOne()?.value1()
		mailer.send(Emails.scheduledReleaseFailed(person.firstName!!, event.draft, event.why, "${baseUrl.trimEnd('/')}/chapters/$code/drafts/${event.changesetId}"), to, person = person.id)
	}
}

/** Releases scheduled drafts when they're due (checked every minute; `portal.drafts.schedule-enabled=false` turns it off). */
@Configuration(proxyBeanMethods = false)
class DraftSchedule(private val drafts: Drafts, @Value("\${portal.drafts.schedule-enabled:true}") private val enabled: Boolean) {
	private val log = LoggerFactory.getLogger(javaClass)

	@Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
	fun releaseDue() {
		if (!enabled) return
		drafts.releaseDue().forEach { (id, result) -> log.info("Scheduled draft {}: {}", id, if (result.released) "released" else "back to draft (${result.conflicts.size} conflicts)") }
	}
}
