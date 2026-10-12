-- Registration and claim (build plan step 10). Tokens and codes are stored only as SHA-256 hashes.

-- Where approved members' Google accounts go (decision 108): first@<google_domain> in <google_org_unit>.
-- The domain defaults to <code>.hack4impact.org when unset.
ALTER TABLE chapter
	ADD COLUMN google_domain   text,
	ADD COLUMN google_org_unit text;

-- The chapter's own registration questions, after the national ones (PRD: chapter-specific form questions).
CREATE TABLE chapter_question (
	id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id uuid NOT NULL REFERENCES chapter (id),
	position   integer NOT NULL,
	label      text NOT NULL,
	kind       text NOT NULL DEFAULT 'text' CHECK (kind IN ('text', 'long_text', 'choice', 'yes_no')),
	options    text[] NOT NULL DEFAULT '{}',
	required   boolean NOT NULL DEFAULT false,
	created_at timestamptz NOT NULL DEFAULT now(),
	UNIQUE (chapter_id, position)
);

-- Lead uploads of accepted members; each row gets a personal, pre-filled registration link.
CREATE TABLE roster_invite (
	id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id      uuid NOT NULL REFERENCES chapter (id),
	uploaded_by     uuid REFERENCES person (id),
	first_name      text NOT NULL,
	last_name       text NOT NULL,
	school_email    text NOT NULL,
	personal_email  text,
	token_hash      text NOT NULL UNIQUE,
	status          text NOT NULL DEFAULT 'sent' CHECK (status IN ('sent', 'started', 'completed', 'expired')),
	expires_at      timestamptz NOT NULL,
	created_at      timestamptz NOT NULL DEFAULT now()
);

-- One application: a new student (new), an imported member claiming their record (claim), or a roster invite (roster).
--   started → submitted → approved | declined (new; roster is approved on submit unless the match is unclear)
--   started → completed (claim: no approval needed)
CREATE TABLE registration (
	id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id         uuid NOT NULL REFERENCES chapter (id),
	kind               text NOT NULL CHECK (kind IN ('new', 'claim', 'roster')),
	status             text NOT NULL DEFAULT 'started' CHECK (status IN ('started', 'submitted', 'approved', 'declined', 'completed')),
	person_id          uuid REFERENCES person (id),       -- claim: the record claimed; after approval: the member
	roster_invite_id   uuid REFERENCES roster_invite (id),
	school_email       text,
	email_verified_at  timestamptz,
	first_name         text,
	last_name          text,
	preferred_name     text,
	personal_email     text,
	graduation_year    integer,
	role_interest      text,
	still_active       boolean,                           -- claim: still in the chapter (else alumni)
	answers            jsonb NOT NULL DEFAULT '{}',       -- chapter question ID → answer
	github_id          text,
	github_login       text,
	github_linked_at   timestamptz,
	github_changed     boolean NOT NULL DEFAULT false,    -- claim: different from the account on record (flagged for leads)
	match_status       text CHECK (match_status IN ('new', 'returning', 'alumni', 'duplicate')),
	matched_person_ids uuid[] NOT NULL DEFAULT '{}',
	submitted_at       timestamptz,
	decided_by         uuid REFERENCES person (id),
	decided_at         timestamptz,
	decline_reason     text,
	created_at         timestamptz NOT NULL DEFAULT now(),
	updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX registration_chapter_status_idx ON registration (chapter_id, status);

-- One-time codes for school email verification: 10 minutes, a few attempts.
CREATE TABLE email_code (
	id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id uuid NOT NULL REFERENCES chapter (id),
	email      text NOT NULL,
	code_hash  text NOT NULL,
	attempts   integer NOT NULL DEFAULT 0,
	expires_at timestamptz NOT NULL,
	used_at    timestamptz,
	created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX email_code_email_idx ON email_code (lower(email), created_at DESC);

-- Applicant sessions (PRD: tied to one registration, at most 7 days, httpOnly cookie).
CREATE TABLE applicant_session (
	id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	registration_id uuid NOT NULL REFERENCES registration (id),
	token_hash      text NOT NULL UNIQUE,
	github_state    text,                                 -- OAuth state while linking GitHub
	expires_at      timestamptz NOT NULL,
	revoked_at      timestamptz,
	created_at      timestamptz NOT NULL DEFAULT now()
);

-- Single-use claim links for imported members (14 days, decision 39), sent to every address on file.
CREATE TABLE claim_link (
	id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	person_id  uuid NOT NULL REFERENCES person (id),
	token_hash text NOT NULL UNIQUE,
	sent_to    text[] NOT NULL,
	expires_at timestamptz NOT NULL,
	used_at    timestamptz,
	created_by uuid REFERENCES person (id),
	created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX claim_link_person_idx ON claim_link (person_id);

-- What the portal emailed, without bodies (they hold codes and links).
CREATE TABLE email_message (
	id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	to_address      text NOT NULL,
	template        text NOT NULL,
	subject         text NOT NULL,
	status          text NOT NULL CHECK (status IN ('sent', 'failed')),
	error           text,
	registration_id uuid REFERENCES registration (id),
	person_id       uuid REFERENCES person (id),
	created_at      timestamptz NOT NULL DEFAULT now()
);
