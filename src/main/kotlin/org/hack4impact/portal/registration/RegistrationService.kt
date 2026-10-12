package org.hack4impact.portal.registration

import org.hack4impact.portal.db.tables.records.RegistrationRecord
import org.hack4impact.portal.db.tables.references.AUDIT_EVENT
import org.hack4impact.portal.db.tables.references.CHAPTER
import org.hack4impact.portal.db.tables.references.CHAPTER_MEMBERSHIP
import org.hack4impact.portal.db.tables.references.CHAPTER_QUESTION
import org.hack4impact.portal.db.tables.references.CLAIM_LINK
import org.hack4impact.portal.db.tables.references.EMAIL_CODE
import org.hack4impact.portal.db.tables.references.INSTITUTION
import org.hack4impact.portal.db.tables.references.PERSON
import org.hack4impact.portal.db.tables.references.REGISTRATION
import org.hack4impact.portal.db.tables.references.ROSTER_INVITE
import org.hack4impact.portal.db.tables.references.TOOL_ACCOUNT
import org.hack4impact.portal.mail.Emails
import org.hack4impact.portal.mail.Mailer
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** Registration, claims and roster invites (build plan step 10; PRD user flows). */
@Component
class RegistrationService(
	private val dsl: DSLContext,
	private val mailer: Mailer,
	private val limiter: RateLimiter,
	private val matcher: RegistrationMatcher,
	private val provisioning: MemberProvisioning,
	private val agreements: org.hack4impact.portal.agreements.AgreementService,
	@Value("\${portal.base-url}") private val baseUrl: String,
) {
	private val json = JsonMapper.builder().build()

	companion object {
		val CODE_LIFETIME: Duration = Duration.ofMinutes(10)
		val LINK_LIFETIME: Duration = Duration.ofDays(14) // claim links and roster invites (decision 39)
		const val CODE_ATTEMPTS = 5
		private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

		private fun bad(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
		private fun tooMany(): Nothing = throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many tries. Wait a little and try again.")
	}

	// ---- The public page ----------------------------------------------------------------------------------------

	fun chapter(code: String): ApplyChapter {
		val c = dsl.select(CHAPTER.ID, CHAPTER.CODE, CHAPTER.NAME, INSTITUTION.EMAIL_DOMAINS, CHAPTER.APPLICATIONS_OPEN, CHAPTER.APPLICATION_URL)
			.from(CHAPTER).leftJoin(INSTITUTION).on(INSTITUTION.ID.eq(CHAPTER.INSTITUTION_ID))
			.where(CHAPTER.CODE.eq(code.lowercase()), CHAPTER.DELETED_AT.isNull, CHAPTER.STATUS.`in`("active", "supported", "pending"))
			.fetchOne() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No chapter $code")
		return ApplyChapter(c.value2()!!, c.value3()!!, c.value4()?.filterNotNull().orEmpty(), asked(c.value1()!!), c.value5()!!, c.value6())
	}

	/**
	 * The chapter's questions applicants answer. None when the chapter runs its own application process: it already
	 * has its answers, and the questions are kept for if it switches back.
	 */
	private fun asked(chapterId: UUID): List<QuestionView> =
		if (dsl.select(CHAPTER.APPLICATIONS_OPEN).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle().value1()!!) questions(chapterId) else emptyList()

	/** New applications go through the chapter's own process when it has turned the portal's off (decision 116). */
	private fun requireOpen(chapter: ApplyChapter) {
		if (!chapter.applicationsOpen) {
			throw ResponseStatusException(HttpStatus.FORBIDDEN, "${chapter.name} runs its own application process" + (chapter.applicationUrl?.let { ": apply at $it" } ?: "."))
		}
	}

	fun questions(chapterId: UUID): List<QuestionView> =
		dsl.selectFrom(CHAPTER_QUESTION).where(CHAPTER_QUESTION.CHAPTER_ID.eq(chapterId)).orderBy(CHAPTER_QUESTION.POSITION)
			.fetch { QuestionView(it.id!!, it.label!!, it.kind!!, it.options!!.filterNotNull(), it.required!!) }

	// ---- New applicants: school email code ----------------------------------------------------------------------

	/** Sends a 6-digit code to a school address. Answers the same whether or not it was sent, past the format check. */
	fun sendCode(code: String, email: String, ip: String) {
		val chapter = chapter(code)
		requireOpen(chapter)
		val address = email.trim().lowercase()
		if (!EMAIL.matches(address)) bad("That doesn't look like an email address")
		val domain = address.substringAfterLast('@')
		if (chapter.emailDomains.isNotEmpty() && chapter.emailDomains.none { domain == it.lowercase() || domain.endsWith(".${it.lowercase()}") }) {
			bad("Use your school address (${chapter.emailDomains.joinToString(" or ") { "@$it" }})")
		}
		if (!limiter.allow("code-email:$address", 5, Duration.ofHours(1)) || !limiter.allow("code-ip:$ip", 30, Duration.ofHours(1))) tooMany()
		val chapterId = chapterId(code)
		val value = Tokens.code()
		dsl.insertInto(EMAIL_CODE).set(EMAIL_CODE.CHAPTER_ID, chapterId).set(EMAIL_CODE.EMAIL, address).set(EMAIL_CODE.CODE_HASH, Tokens.hash(value))
			.set(EMAIL_CODE.EXPIRES_AT, OffsetDateTime.now().plus(CODE_LIFETIME)).execute()
		mailer.send(Emails.code(chapter.name, value), address)
	}

	/**
	 * Checks the newest code for this address; on success returns the registration to start a session for. A wrong
	 * code is counted even though the request fails (no rollback), so the attempt limit holds.
	 */
	@Transactional(noRollbackFor = [ResponseStatusException::class])
	fun verifyCode(code: String, email: String, value: String, ip: String): UUID {
		if (!limiter.allow("verify-ip:$ip", 60, Duration.ofHours(1))) tooMany()
		requireOpen(chapter(code))
		val chapterId = chapterId(code)
		val address = email.trim().lowercase()
		val row = dsl.selectFrom(EMAIL_CODE)
			.where(EMAIL_CODE.CHAPTER_ID.eq(chapterId), DSL.lower(EMAIL_CODE.EMAIL).eq(address), EMAIL_CODE.USED_AT.isNull)
			.orderBy(EMAIL_CODE.CREATED_AT.desc()).limit(1).forUpdate().fetchOne()
		if (row == null || row.expiresAt!!.isBefore(OffsetDateTime.now())) bad("That code has expired. Ask for a new one.")
		if (row.attempts!! >= CODE_ATTEMPTS) bad("Too many wrong codes. Ask for a new one.")
		if (!Tokens.same(row.codeHash!!, Tokens.hash(value.trim()))) {
			dsl.update(EMAIL_CODE).set(EMAIL_CODE.ATTEMPTS, row.attempts!! + 1).where(EMAIL_CODE.ID.eq(row.id)).execute()
			bad("That code isn't right")
		}
		dsl.update(EMAIL_CODE).set(EMAIL_CODE.USED_AT, OffsetDateTime.now()).where(EMAIL_CODE.ID.eq(row.id)).execute()
		// Pick up an unfinished application from the same address rather than starting over.
		dsl.select(REGISTRATION.ID).from(REGISTRATION)
			.where(REGISTRATION.CHAPTER_ID.eq(chapterId), DSL.lower(REGISTRATION.SCHOOL_EMAIL).eq(address), REGISTRATION.KIND.eq("new"), REGISTRATION.STATUS.`in`("started", "submitted"))
			.orderBy(REGISTRATION.CREATED_AT.desc()).limit(1).fetchOne()?.value1()?.let { return it }
		return dsl.insertInto(REGISTRATION).set(REGISTRATION.CHAPTER_ID, chapterId).set(REGISTRATION.KIND, "new").set(REGISTRATION.SCHOOL_EMAIL, address)
			.set(REGISTRATION.EMAIL_VERIFIED_AT, OffsetDateTime.now()).returningResult(REGISTRATION.ID).fetchSingle().value1()!!
	}

	// ---- Claim links and roster invites ---------------------------------------------------------------------------

	/** Opens a claim link (single use): a claim registration pre-filled from the imported record. */
	@Transactional
	fun openClaim(token: String, ip: String): UUID {
		if (!limiter.allow("link-ip:$ip", 60, Duration.ofHours(1))) tooMany()
		val link = dsl.selectFrom(CLAIM_LINK).where(CLAIM_LINK.TOKEN_HASH.eq(Tokens.hash(token))).forUpdate().fetchOne()
			?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link isn't valid. Ask national for a new one.")
		if (link.usedAt != null) throw ResponseStatusException(HttpStatus.GONE, "This link was already used. Ask national for a new one.")
		if (link.expiresAt!!.isBefore(OffsetDateTime.now())) throw ResponseStatusException(HttpStatus.GONE, "This link has expired. Ask national for a new one.")
		dsl.update(CLAIM_LINK).set(CLAIM_LINK.USED_AT, OffsetDateTime.now()).where(CLAIM_LINK.ID.eq(link.id)).execute()
		val p = dsl.selectFrom(PERSON).where(PERSON.ID.eq(link.personId)).fetchSingle()
		if (p.claimedAt != null) throw ResponseStatusException(HttpStatus.GONE, "This record is already claimed. Sign in with your Hack4Impact Google account.")
		val chapter = dsl.select(CHAPTER_MEMBERSHIP.CHAPTER_ID).from(CHAPTER_MEMBERSHIP).where(CHAPTER_MEMBERSHIP.PERSON_ID.eq(p.id))
			.orderBy(CHAPTER_MEMBERSHIP.LEFT_ON.desc().nullsFirst()).limit(1).fetchOne()?.value1()
			?: throw ResponseStatusException(HttpStatus.CONFLICT, "This record has no chapter. Ask national.")
		return dsl.insertInto(REGISTRATION).set(REGISTRATION.CHAPTER_ID, chapter).set(REGISTRATION.KIND, "claim").set(REGISTRATION.PERSON_ID, p.id)
			.set(REGISTRATION.SCHOOL_EMAIL, p.schoolEmail).set(REGISTRATION.EMAIL_VERIFIED_AT, OffsetDateTime.now())
			.set(REGISTRATION.FIRST_NAME, p.firstName).set(REGISTRATION.LAST_NAME, p.lastName).set(REGISTRATION.PREFERRED_NAME, p.preferredName)
			.set(REGISTRATION.PERSONAL_EMAIL, p.personalEmail)
			.returningResult(REGISTRATION.ID).fetchSingle().value1()!!
	}

	/** Opens a roster invite: pre-filled, the school address already proven (the link went there). Reusable until done. */
	@Transactional
	fun openRoster(token: String, ip: String): UUID {
		if (!limiter.allow("link-ip:$ip", 60, Duration.ofHours(1))) tooMany()
		val invite = dsl.selectFrom(ROSTER_INVITE).where(ROSTER_INVITE.TOKEN_HASH.eq(Tokens.hash(token))).forUpdate().fetchOne()
			?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This invite isn't valid. Ask your chapter's leads.")
		if (invite.status == "completed") throw ResponseStatusException(HttpStatus.GONE, "You've already joined. Sign in with your Hack4Impact Google account.")
		if (invite.expiresAt!!.isBefore(OffsetDateTime.now())) throw ResponseStatusException(HttpStatus.GONE, "This invite has expired. Ask your chapter's leads for a new one.")
		dsl.select(REGISTRATION.ID).from(REGISTRATION).where(REGISTRATION.ROSTER_INVITE_ID.eq(invite.id)).fetchOne()?.value1()?.let { return it }
		dsl.update(ROSTER_INVITE).set(ROSTER_INVITE.STATUS, "started").where(ROSTER_INVITE.ID.eq(invite.id)).execute()
		return dsl.insertInto(REGISTRATION).set(REGISTRATION.CHAPTER_ID, invite.chapterId).set(REGISTRATION.KIND, "roster").set(REGISTRATION.ROSTER_INVITE_ID, invite.id)
			.set(REGISTRATION.SCHOOL_EMAIL, invite.schoolEmail).set(REGISTRATION.EMAIL_VERIFIED_AT, OffsetDateTime.now())
			.set(REGISTRATION.FIRST_NAME, invite.firstName).set(REGISTRATION.LAST_NAME, invite.lastName).set(REGISTRATION.PERSONAL_EMAIL, invite.personalEmail)
			.returningResult(REGISTRATION.ID).fetchSingle().value1()!!
	}

	// ---- The applicant's own registration -------------------------------------------------------------------------

	fun view(registration: UUID, githubConfigured: Boolean): ApplicantView {
		val r = record(registration)
		val code = dsl.select(CHAPTER.CODE).from(CHAPTER).where(CHAPTER.ID.eq(r.chapterId)).fetchSingle().value1()!!
		val account = r.personId?.takeIf { r.status in setOf("approved", "completed") }?.let { p ->
			dsl.select(PERSON.ORG_EMAIL).from(PERSON).where(PERSON.ID.eq(p)).fetchSingle().value1()
		}
		// First, so a signature just made in Documenso already counts in what's missing.
		val signing = if (r.status == "started") agreements.applicantAgreements(registration) else emptyList()
		return ApplicantView(
			r.kind!!, r.status!!, chapter(code), r.schoolEmail, r.emailVerifiedAt != null, r.githubLogin, githubConfigured,
			Details(r.firstName, r.lastName, r.preferredName, r.personalEmail, r.graduationYear, r.roleInterest, r.stillActive),
			answers(r), missing(r), r.declineReason, account,
			signing,
		)
	}

	@Transactional
	fun save(registration: UUID, input: DetailsInput) {
		val r = record(registration)
		if (r.status != "started") throw ResponseStatusException(HttpStatus.CONFLICT, "This application was already sent")
		val d = input.details
		d.personalEmail?.takeIf { it.isNotBlank() }?.let { if (!EMAIL.matches(it.trim())) bad("That personal email doesn't look right") }
		d.graduationYear?.let { if (it !in 2000..2100) bad("Graduation year should look like 2028") }
		val known = questions(r.chapterId!!).map { it.id.toString() }.toSet()
		val answers = input.answers.filterKeys { it in known }.mapValues { it.value.take(4000) }
		dsl.update(REGISTRATION)
			.set(REGISTRATION.FIRST_NAME, d.firstName?.trim()).set(REGISTRATION.LAST_NAME, d.lastName?.trim())
			.set(REGISTRATION.PREFERRED_NAME, d.preferredName?.trim()?.ifEmpty { null }).set(REGISTRATION.PERSONAL_EMAIL, d.personalEmail?.trim()?.lowercase()?.ifEmpty { null })
			.set(REGISTRATION.GRADUATION_YEAR, d.graduationYear).set(REGISTRATION.ROLE_INTEREST, d.roleInterest?.trim()?.ifEmpty { null })
			.set(REGISTRATION.STILL_ACTIVE, d.stillActive).set(REGISTRATION.ANSWERS, JSONB.valueOf(json.writeValueAsString(answers)))
			.set(REGISTRATION.UPDATED_AT, OffsetDateTime.now())
			.where(REGISTRATION.ID.eq(registration)).execute()
	}

	/** Records the GitHub account the applicant signed in with; for claims, flags a different account than on record. */
	@Transactional
	fun linkGitHub(registration: UUID, user: GitHubUser) {
		val r = record(registration)
		val changed = r.kind == "claim" && r.personId != null && dsl.select(TOOL_ACCOUNT.EXTERNAL_ID, TOOL_ACCOUNT.EXTERNAL_LOGIN).from(TOOL_ACCOUNT)
			.where(TOOL_ACCOUNT.PERSON_ID.eq(r.personId), TOOL_ACCOUNT.TOOL.eq("github")).fetch()
			.let { onRecord -> onRecord.isNotEmpty() && onRecord.none { it.value1() == user.id || it.value2().equals(user.login, ignoreCase = true) } }
		dsl.update(REGISTRATION).set(REGISTRATION.GITHUB_ID, user.id).set(REGISTRATION.GITHUB_LOGIN, user.login)
			.set(REGISTRATION.GITHUB_LINKED_AT, OffsetDateTime.now()).set(REGISTRATION.GITHUB_CHANGED, changed)
			.where(REGISTRATION.ID.eq(registration)).execute()
	}

	/**
	 * Sends the application. New: matched and queued for the leads. Roster: approved at once when the match is clear,
	 * else queued like a new one. Claim: completed (no approval needed, PRD).
	 */
	fun submit(registration: UUID) {
		val r = record(registration)
		if (r.status != "started") throw ResponseStatusException(HttpStatus.CONFLICT, "This application was already sent")
		missing(r).takeIf { it.isNotEmpty() }?.let { bad("Still needed: ${it.joinToString(", ")}") }
		if (r.kind == "new") requireOpen(chapter(dsl.select(CHAPTER.CODE).from(CHAPTER).where(CHAPTER.ID.eq(r.chapterId)).fetchSingle().value1()!!))
		if (r.kind == "claim") {
			provisioning.provision(registration, null, null)
			return
		}
		val match = matcher.match(r)
		dsl.update(REGISTRATION).set(REGISTRATION.STATUS, "submitted").set(REGISTRATION.SUBMITTED_AT, OffsetDateTime.now())
			.set(REGISTRATION.MATCH_STATUS, match.status).set(REGISTRATION.MATCHED_PERSON_IDS, match.people.keys.toTypedArray())
			.where(REGISTRATION.ID.eq(registration)).execute()
		if (r.kind == "roster" && match.status != "duplicate") {
			provisioning.provision(registration, match.people.keys.singleOrNull(), null)
		}
	}

	private fun missing(r: RegistrationRecord): List<String> = buildList {
		if (r.emailVerifiedAt == null) add("your school email")
		if (r.githubId == null) add("your GitHub account")
		if (r.status == "started") addAll(agreements.missingFor(r.id!!))
		if (r.firstName.isNullOrBlank() || r.lastName.isNullOrBlank()) add("your name")
		if (r.kind == "claim") {
			if (r.stillActive == null) add("whether you're still in the chapter")
		} else {
			if (r.graduationYear == null) add("your graduation year")
			val answers = answers(r)
			asked(r.chapterId!!).filter { it.required && answers[it.id.toString()].isNullOrBlank() }.forEach { add("“${it.label}”") }
		}
	}

	private fun answers(r: RegistrationRecord): Map<String, String> =
		json.readTree(r.answers?.data() ?: "{}").properties().associate { it.key to it.value.asString() }

	/** Starts signing one registration agreement (PRD: the volunteer agreement and code of conduct, through Documenso). */
	fun startAgreement(registration: UUID, kind: String): AgreementStart {
		val r = record(registration)
		if (r.status != "started") throw ResponseStatusException(HttpStatus.CONFLICT, "This application was already sent")
		if (r.firstName.isNullOrBlank() || r.lastName.isNullOrBlank()) bad("Save your name first: it goes on the agreement")
		return try {
			AgreementStart(agreements.startRegistrationAgreement(registration, kind))
		} catch (e: IllegalArgumentException) {
			bad(e.message ?: "No such agreement")
		} catch (e: IllegalStateException) {
			throw ResponseStatusException(HttpStatus.CONFLICT, e.message)
		}
	}

	// ---- Leads --------------------------------------------------------------------------------------------------------

	fun registrations(chapterId: UUID): List<RegistrationSummary> {
		val labels = questions(chapterId).associate { it.id.toString() to it.label }
		val since = OffsetDateTime.now().minusDays(30)
		return dsl.selectFrom(REGISTRATION)
			.where(REGISTRATION.CHAPTER_ID.eq(chapterId), REGISTRATION.KIND.ne("claim"),
				REGISTRATION.STATUS.eq("submitted").or(REGISTRATION.STATUS.`in`("approved", "declined").and(REGISTRATION.DECIDED_AT.gt(since))))
			.orderBy(REGISTRATION.STATUS.desc(), REGISTRATION.SUBMITTED_AT)
			.fetch { r ->
				RegistrationSummary(
					r.id!!, r.kind!!, r.status!!, listOfNotNull(r.preferredName ?: r.firstName, r.lastName).joinToString(" "), r.schoolEmail, r.githubLogin,
					r.graduationYear, r.roleInterest, r.matchStatus, matches(r), answers(r).mapKeys { labels[it.key] ?: "?" }, r.githubChanged!!,
					r.submittedAt, r.decidedAt, r.declineReason,
				)
			}
	}

	private fun matches(r: RegistrationRecord): List<MatchView> {
		if (r.status != "submitted") return emptyList()
		val found = matcher.match(r).people
		if (found.isEmpty()) return emptyList()
		val chapters = dsl.select(CHAPTER_MEMBERSHIP.PERSON_ID, CHAPTER.NAME).from(CHAPTER_MEMBERSHIP).join(CHAPTER).on(CHAPTER.ID.eq(CHAPTER_MEMBERSHIP.CHAPTER_ID))
			.where(CHAPTER_MEMBERSHIP.PERSON_ID.`in`(found.keys)).fetch().groupBy({ it.value1()!! }, { it.value2()!! })
		return dsl.selectFrom(PERSON).where(PERSON.ID.`in`(found.keys)).fetch { p ->
			MatchView(p.id!!, "${p.preferredName?.takeIf { it.isNotBlank() } ?: p.firstName} ${p.lastName}", p.status!!, chapters[p.id].orEmpty(), found.getValue(p.id!!))
		}
	}

	fun approve(chapterId: UUID, registration: UUID, input: ApproveInput, decidedBy: UUID?) {
		val r = record(registration)
		if (r.chapterId != chapterId) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not this chapter's")
		if (r.status != "submitted") throw ResponseStatusException(HttpStatus.CONFLICT, "This application is ${r.status}")
		input.personId?.let { if (it !in r.matchedPersonIds!!.filterNotNull()) bad("Approve as a new person or as one of the matches") }
		provisioning.provision(registration, input.personId, decidedBy)
	}

	@Transactional
	fun decline(chapterId: UUID, registration: UUID, reason: String, decidedBy: UUID?, sessions: ApplicantSessions) {
		val r = record(registration)
		if (r.chapterId != chapterId) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not this chapter's")
		if (r.status != "submitted") throw ResponseStatusException(HttpStatus.CONFLICT, "This application is ${r.status}")
		if (reason.isBlank()) bad("Say why: the applicant gets the reason by email (PRD)")
		dsl.update(REGISTRATION).set(REGISTRATION.STATUS, "declined").set(REGISTRATION.DECLINE_REASON, reason.trim())
			.set(REGISTRATION.DECIDED_BY, decidedBy).set(REGISTRATION.DECIDED_AT, OffsetDateTime.now()).where(REGISTRATION.ID.eq(registration)).execute()
		sessions.revoke(registration)
		val chapter = dsl.select(CHAPTER.NAME).from(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle().value1()!!
		listOfNotNull(r.personalEmail, r.schoolEmail).distinct().forEach { mailer.send(Emails.declined(r.firstName ?: "there", chapter, reason.trim()), it, registration) }
		audit(decidedBy, "registration.decline", "registration", registration, chapterId)
	}

	// ---- Chapter settings: questions and where accounts go ------------------------------------------------------------

	fun settings(chapterId: UUID, canEditQuestions: Boolean, national: Boolean): RegistrationSettings {
		val c = dsl.selectFrom(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle()
		return RegistrationSettings(
			questions(chapterId), c.googleDomain ?: "${c.code}.hack4impact.org", c.googleOrgUnit, c.applicationsOpen!!, c.applicationUrl, canEditQuestions, national,
		)
	}

	@Transactional
	fun saveSettings(chapterId: UUID, input: RegistrationSettingsInput, national: Boolean, actor: UUID?) {
		input.questions.forEachIndexed { i, q ->
			if (q.label.isBlank()) bad("Question ${i + 1} needs a label")
			if (q.kind !in setOf("text", "long_text", "choice", "yes_no")) bad("Question ${i + 1}: unknown kind")
			if (q.kind == "choice" && q.options.count { it.isNotBlank() } < 2) bad("Question ${i + 1}: a choice needs at least two options")
		}
		val url = input.applicationUrl?.trim()?.ifEmpty { null }
		if (url != null && !Regex("^https://[^\\s]+$").matches(url)) bad("The application link should start with https://")
		// Replaced as a list; answers keep pointing at questions that stay (same ID when the label and position match).
		val existing = dsl.selectFrom(CHAPTER_QUESTION).where(CHAPTER_QUESTION.CHAPTER_ID.eq(chapterId)).fetch().associateBy { it.label!! to it.position!! }
		dsl.deleteFrom(CHAPTER_QUESTION).where(CHAPTER_QUESTION.CHAPTER_ID.eq(chapterId)).execute()
		input.questions.forEachIndexed { i, q ->
			dsl.insertInto(CHAPTER_QUESTION).set(CHAPTER_QUESTION.ID, existing[q.label.trim() to i + 1]?.id ?: UUID.randomUUID())
				.set(CHAPTER_QUESTION.CHAPTER_ID, chapterId).set(CHAPTER_QUESTION.POSITION, i + 1).set(CHAPTER_QUESTION.LABEL, q.label.trim())
				.set(CHAPTER_QUESTION.KIND, q.kind).set(CHAPTER_QUESTION.OPTIONS, q.options.map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
				.set(CHAPTER_QUESTION.REQUIRED, q.required).execute()
		}
		input.applicationsOpen?.let { open ->
			dsl.update(CHAPTER).set(CHAPTER.APPLICATIONS_OPEN, open).set(CHAPTER.APPLICATION_URL, url).where(CHAPTER.ID.eq(chapterId)).execute()
		}
		if (national) {
			dsl.update(CHAPTER).set(CHAPTER.GOOGLE_DOMAIN, input.googleDomain?.trim()?.lowercase()?.ifEmpty { null })
				.set(CHAPTER.GOOGLE_ORG_UNIT, input.googleOrgUnit?.takeIf { it.isNotBlank() }?.let(org.hack4impact.portal.adapters.google.GoogleAccounts::orgUnitPath)).where(CHAPTER.ID.eq(chapterId)).execute()
		}
		audit(actor, "chapter.registration_settings", "chapter", chapterId, chapterId)
	}

	// ---- Roster invites -------------------------------------------------------------------------------------------------

	/** Parses a roster CSV (first_name, last_name, school_email, optional personal_email) and invites each row. */
	@Transactional
	fun roster(chapterId: UUID, csv: String, actor: UUID?): RosterResult {
		val chapter = dsl.selectFrom(CHAPTER).where(CHAPTER.ID.eq(chapterId)).fetchSingle()
		val lines = csv.lines().map { it.trim() }.filter { it.isNotEmpty() }
		if (lines.isEmpty()) bad("The roster is empty")
		val header = lines.first().split(',').map { it.trim().lowercase().replace(' ', '_') }
		val col = { name: String -> header.indexOf(name) }
		if (col("first_name") < 0 || col("last_name") < 0 || col("school_email") < 0) bad("The first line needs columns first_name, last_name, school_email (and optionally personal_email)")
		if (lines.size > 501) bad("At most 500 people per roster")
		var invited = 0
		val skipped = mutableListOf<String>()
		for ((n, line) in lines.drop(1).withIndex()) {
			val cells = line.split(',').map { it.trim().trim('"') }
			fun cell(name: String) = col(name).takeIf { it >= 0 }?.let { cells.getOrNull(it) }?.takeIf { it.isNotEmpty() }
			val first = cell("first_name"); val last = cell("last_name"); val school = cell("school_email")?.lowercase()
			if (first == null || last == null || school == null || !EMAIL.matches(school)) { skipped += "row ${n + 2}: needs a name and a school email"; continue }
			if (dsl.fetchExists(ROSTER_INVITE, ROSTER_INVITE.CHAPTER_ID.eq(chapterId), DSL.lower(ROSTER_INVITE.SCHOOL_EMAIL).eq(school), ROSTER_INVITE.STATUS.ne("expired"))) {
				skipped += "row ${n + 2}: $school is already invited"; continue
			}
			val token = Tokens.new()
			dsl.insertInto(ROSTER_INVITE).set(ROSTER_INVITE.CHAPTER_ID, chapterId).set(ROSTER_INVITE.UPLOADED_BY, actor).set(ROSTER_INVITE.FIRST_NAME, first)
				.set(ROSTER_INVITE.LAST_NAME, last).set(ROSTER_INVITE.SCHOOL_EMAIL, school).set(ROSTER_INVITE.PERSONAL_EMAIL, cell("personal_email")?.lowercase())
				.set(ROSTER_INVITE.TOKEN_HASH, Tokens.hash(token)).set(ROSTER_INVITE.EXPIRES_AT, OffsetDateTime.now().plus(LINK_LIFETIME)).execute()
			mailer.send(Emails.rosterInvite(first, chapter.name!!, "${baseUrl.trimEnd('/')}/join/${chapter.code}?invite=$token"), school)
			invited++
		}
		audit(actor, "roster.upload", "chapter", chapterId, chapterId)
		return RosterResult(invited, skipped, rosterInvites(chapterId))
	}

	fun rosterInvites(chapterId: UUID): List<RosterInviteView> =
		dsl.selectFrom(ROSTER_INVITE).where(ROSTER_INVITE.CHAPTER_ID.eq(chapterId)).orderBy(ROSTER_INVITE.CREATED_AT.desc()).limit(500).fetch {
			val status = if (it.status in setOf("sent", "started") && it.expiresAt!!.isBefore(OffsetDateTime.now())) "expired" else it.status!!
			RosterInviteView(it.id!!, "${it.firstName} ${it.lastName}", it.schoolEmail!!, status, it.createdAt!!, it.expiresAt!!)
		}

	// ---- Claim links (national) ---------------------------------------------------------------------------------------------

	/** Imported, unclaimed, not removed, with at least one address: who a claim link can go to. */
	private fun claimable(chapter: String?) = dsl.selectDistinct(PERSON.ID, PERSON.FIRST_NAME, PERSON.SCHOOL_EMAIL, PERSON.PERSONAL_EMAIL, PERSON.ORG_EMAIL).from(PERSON)
		.join(CHAPTER_MEMBERSHIP).on(CHAPTER_MEMBERSHIP.PERSON_ID.eq(PERSON.ID)).join(CHAPTER).on(CHAPTER.ID.eq(CHAPTER_MEMBERSHIP.CHAPTER_ID))
		.where(PERSON.SOURCE_ID.isNotNull, PERSON.CLAIMED_AT.isNull, PERSON.STATUS.ne("removed"), PERSON.DELETED_AT.isNull,
			if (chapter == null) DSL.noCondition() else CHAPTER.CODE.eq(chapter.lowercase()))
		.fetch()

	fun claimPreview(chapter: String?): ClaimPreview {
		val people = claimable(chapter)
		val sent = dsl.select(CLAIM_LINK.PERSON_ID).from(CLAIM_LINK).where(CLAIM_LINK.EXPIRES_AT.gt(OffsetDateTime.now()), CLAIM_LINK.USED_AT.isNull).fetch().map { it.value1() }.toSet()
		val hasEmail = { p: org.jooq.Record5<UUID?, String?, String?, String?, String?> -> listOfNotNull(p.value3(), p.value4(), p.value5()).isNotEmpty() }
		return ClaimPreview(
			eligible = people.count { it.value1() !in sent && hasEmail(it) },
			alreadySent = people.count { it.value1() in sent },
			withoutEmail = people.count { !hasEmail(it) },
		)
	}

	/**
	 * Sends claim links (PRD: to every address on file; 14 days, single use) to people without an open one, at most
	 * [limit] per call so a mistake can't email everyone at once.
	 */
	fun sendClaims(chapter: String?, limit: Int, actor: UUID?): Int {
		val open = dsl.select(CLAIM_LINK.PERSON_ID).from(CLAIM_LINK).where(CLAIM_LINK.EXPIRES_AT.gt(OffsetDateTime.now()), CLAIM_LINK.USED_AT.isNull).fetch().map { it.value1() }.toSet()
		var sent = 0
		for (p in claimable(chapter)) {
			if (sent >= limit.coerceIn(1, 1000)) break
			if (p.value1() in open) continue
			val addresses = listOfNotNull(p.value3(), p.value4(), p.value5()).map { it.trim().lowercase() }.distinct()
			if (addresses.isEmpty()) continue
			val token = Tokens.new()
			dsl.insertInto(CLAIM_LINK).set(CLAIM_LINK.PERSON_ID, p.value1()).set(CLAIM_LINK.TOKEN_HASH, Tokens.hash(token)).set(CLAIM_LINK.SENT_TO, addresses.toTypedArray())
				.set(CLAIM_LINK.EXPIRES_AT, OffsetDateTime.now().plus(LINK_LIFETIME)).set(CLAIM_LINK.CREATED_BY, actor).execute()
			val email = Emails.claim(p.value2() ?: "there", "${baseUrl.trimEnd('/')}/claim/$token", LINK_LIFETIME.toDays().toInt())
			addresses.forEach { mailer.send(email, it, person = p.value1()) }
			sent++
		}
		audit(actor, "claims.send", "chapter", null, null, """{"sent":$sent,"chapter":${chapter?.let { "\"$it\"" } ?: "null"}}""")
		return sent
	}

	// ---- Helpers --------------------------------------------------------------------------------------------------------

	fun record(id: UUID): RegistrationRecord = dsl.selectFrom(REGISTRATION).where(REGISTRATION.ID.eq(id)).fetchOne()
		?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such registration")

	private fun chapterId(code: String) = dsl.select(CHAPTER.ID).from(CHAPTER).where(CHAPTER.CODE.eq(code.lowercase())).fetchOne()?.value1()
		?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No chapter $code")

	private fun audit(actor: UUID?, action: String, targetType: String, target: UUID?, chapter: UUID?, after: String? = null) {
		dsl.insertInto(AUDIT_EVENT)
			.set(AUDIT_EVENT.ACTOR_TYPE, if (actor != null) "person" else "system").set(AUDIT_EVENT.ACTOR_PERSON_ID, actor)
			.set(AUDIT_EVENT.ACTION, action).set(AUDIT_EVENT.TARGET_TYPE, targetType).set(AUDIT_EVENT.TARGET_ID, target).set(AUDIT_EVENT.CHAPTER_ID, chapter)
			.set(AUDIT_EVENT.AFTER, after?.let { JSONB.valueOf(it) })
			.execute()
	}
}
