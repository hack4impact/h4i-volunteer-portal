package org.hack4impact.portal.registration

import java.time.OffsetDateTime
import java.util.UUID

// Applicant side (/api/apply): an applicant only ever sees their own registration.

data class QuestionView(val id: UUID, val label: String, val kind: String, val options: List<String>, val required: Boolean)

/** A chapter's public registration page. [emailDomains]: school addresses it accepts (empty = any). */
data class ApplyChapter(
	val code: String,
	val name: String,
	val emailDomains: List<String>,
	val questions: List<QuestionView>,
	/** False when the chapter runs its own application process; then [applicationUrl] is where to apply, if set. */
	val applicationsOpen: Boolean,
	val applicationUrl: String?,
)

data class CodeRequest(val email: String)

data class VerifyRequest(val email: String, val code: String)

data class TokenRequest(val token: String)

data class Details(
	val firstName: String? = null,
	val lastName: String? = null,
	val preferredName: String? = null,
	val personalEmail: String? = null,
	val graduationYear: Int? = null,
	val roleInterest: String? = null,
	/** Claims: still in the chapter (else they stay as an alumnus). */
	val stillActive: Boolean? = null,
)

data class DetailsInput(val details: Details, val answers: Map<String, String> = emptyMap())

data class ApplicantView(
	val kind: String,
	/** started, submitted, approved, declined or completed. */
	val status: String,
	val chapter: ApplyChapter,
	val schoolEmail: String?,
	val emailVerified: Boolean,
	val githubLogin: String?,
	val githubConfigured: Boolean,
	val details: Details,
	val answers: Map<String, String>,
	/** What's still needed before submitting, in words. */
	val missing: List<String>,
	val declineReason: String?,
	/** After approval or a claim: the member's Google address, if one was created. */
	val account: String?,
	/** The volunteer agreement and code of conduct to sign before sending (none until national sets them up). */
	val agreements: List<org.hack4impact.portal.agreements.ApplicantAgreement> = emptyList(),
)

/** Where to sign: a Documenso page that comes back to /apply. */
data class AgreementStart(val url: String)

// Lead side (/api/chapters/{code}/registrations).

data class MatchView(val personId: UUID, val name: String, val status: String, val chapters: List<String>, val why: List<String>)

data class RegistrationSummary(
	val id: UUID,
	val kind: String,
	val status: String,
	val name: String,
	val schoolEmail: String?,
	val githubLogin: String?,
	val graduationYear: Int?,
	val roleInterest: String?,
	/** new, returning, alumni (record found) or duplicate (possible duplicate). */
	val matchStatus: String?,
	val matches: List<MatchView>,
	/** Chapter question label → answer. */
	val answers: Map<String, String>,
	val githubChanged: Boolean,
	val submittedAt: OffsetDateTime?,
	val decidedAt: OffsetDateTime?,
	val declineReason: String?,
)

/** Approve as a new person, or as [personId] (one of the matches). */
data class ApproveInput(val personId: UUID? = null)

data class DeclineInput(val reason: String)

data class QuestionInput(val label: String, val kind: String = "text", val options: List<String> = emptyList(), val required: Boolean = false)

data class RegistrationSettings(
	val questions: List<QuestionView>,
	/** New members' Google accounts: first@[googleDomain] in [googleOrgUnit]. National sets these. */
	val googleDomain: String,
	val googleOrgUnit: String?,
	/** False when the chapter runs its own application process (roster invites still work). */
	val applicationsOpen: Boolean,
	val applicationUrl: String?,
	val canEditQuestions: Boolean,
	val canEditAccounts: Boolean,
)

data class RegistrationSettingsInput(
	val questions: List<QuestionInput>,
	val googleDomain: String? = null,
	val googleOrgUnit: String? = null,
	/** Null leaves it as it is. */
	val applicationsOpen: Boolean? = null,
	val applicationUrl: String? = null,
)

data class RosterInput(val csv: String)

data class RosterInviteView(val id: UUID, val name: String, val schoolEmail: String, val status: String, val createdAt: OffsetDateTime, val expiresAt: OffsetDateTime)

data class RosterResult(val invited: Int, val skipped: List<String>, val invites: List<RosterInviteView>)

data class ClaimPreview(val eligible: Int, val alreadySent: Int, val withoutEmail: Int)

data class ClaimSend(val chapter: String? = null, val limit: Int = 200)
