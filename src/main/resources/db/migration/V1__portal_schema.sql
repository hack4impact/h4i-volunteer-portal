-- Portal schema, version 1 (build plan step 2).
--
-- The portal's own database is the source of truth (wiki decision 23). Rows copied from the national
-- member database carry `source_id` (the member-DB primary key) and `imported_at`; the importer
-- only updates such a row while `updated_at <= imported_at`, i.e. while nobody has edited it here.
--
-- Status-like columns are text with CHECK constraints rather than Postgres enums, so adding a
-- value is a one-line migration. Access levels are per tool and validated in application code.
-- Tables for registration (step 10), adoption (7), Notion routes (8) and offboarding (13) are added
-- by those steps' migrations.

CREATE FUNCTION set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
	NEW.updated_at = now();
	RETURN NEW;
END
$$;

CREATE DOMAIN tool_name AS text
	CHECK (VALUE IN ('google', 'github', 'slack', 'notion', 'vaultwarden', 'documenso'));

-- Schools, chapters, semesters ---------------------------------------------------------------

CREATE TABLE institution (
	id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	name          text NOT NULL UNIQUE,
	email_domains text[] NOT NULL DEFAULT '{}', -- school email domains accepted for verification
	source_id     uuid UNIQUE,
	imported_at   timestamptz,
	created_at    timestamptz NOT NULL DEFAULT now(),
	updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE term (
	id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	institution_id uuid NOT NULL REFERENCES institution (id),
	season         text NOT NULL CHECK (season IN ('spring', 'summer', 'fall', 'winter')),
	year           smallint NOT NULL,
	code           text,
	starts_on      date NOT NULL,
	ends_on        date NOT NULL,
	source_id      uuid UNIQUE,
	imported_at    timestamptz,
	created_at     timestamptz NOT NULL DEFAULT now(),
	updated_at     timestamptz NOT NULL DEFAULT now(),
	UNIQUE (institution_id, season, year),
	CHECK (ends_on > starts_on)
);

CREATE TABLE chapter (
	id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	code                   text NOT NULL UNIQUE CHECK (code ~ '^[a-z0-9][a-z0-9_-]*$'), -- slug in join links and tool names, e.g. umd
	name                   text NOT NULL UNIQUE,
	institution_id         uuid REFERENCES institution (id),
	status                 text NOT NULL DEFAULT 'active'
		CHECK (status IN ('active', 'inactive', 'pending', 'supported', 'suspended')),
	website                text,
	email                  text,
	reconfirmation_enabled boolean NOT NULL DEFAULT true,
	grandfather_until      date,
	notion_teamspace_id    text,
	source_id              uuid UNIQUE,
	imported_at            timestamptz,
	created_at             timestamptz NOT NULL DEFAULT now(),
	updated_at             timestamptz NOT NULL DEFAULT now(),
	deleted_at             timestamptz
);

-- People --------------------------------------------------------------------------------------

CREATE TABLE person (
	id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	first_name         text NOT NULL,
	last_name          text NOT NULL,
	preferred_name     text,
	school_email       text,
	personal_email     text,
	org_email          text, -- @hack4impact.org
	phone              text,
	graduation_term_id uuid REFERENCES term (id),
	status             text NOT NULL DEFAULT 'applicant'
		CHECK (status IN ('applicant', 'active', 'alumni', 'removal_requested', 'removed')),
	kind               text NOT NULL DEFAULT 'student' CHECK (kind IN ('student', 'community')),
	claimed_at         timestamptz, -- the person confirmed their details; imported fields are unverified before this
	source_id          uuid UNIQUE,
	source_status      text,        -- member-DB status/type as imported, kept for review
	imported_at        timestamptz,
	created_at         timestamptz NOT NULL DEFAULT now(),
	updated_at         timestamptz NOT NULL DEFAULT now(),
	deleted_at         timestamptz
);
-- Not unique yet: the member DB has duplicates. Uniqueness is enforced once claims have cleaned them up.
CREATE INDEX person_school_email_idx ON person (lower(school_email));
CREATE INDEX person_personal_email_idx ON person (lower(personal_email));
CREATE INDEX person_org_email_idx ON person (lower(org_email));

CREATE TABLE chapter_membership (
	person_id  uuid NOT NULL REFERENCES person (id),
	chapter_id uuid NOT NULL REFERENCES chapter (id),
	joined_on  date,
	left_on    date,
	created_at timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (person_id, chapter_id)
);

CREATE TABLE chapter_role (
	id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	person_id  uuid NOT NULL REFERENCES person (id),
	chapter_id uuid NOT NULL REFERENCES chapter (id),
	role       text NOT NULL CHECK (role IN ('lead', 'co_lead', 'viewer')),
	title      text, -- e.g. "Director of product"; used by automation rules, not permissions
	starts_at  timestamptz NOT NULL DEFAULT now(),
	ends_at    timestamptz,
	created_at timestamptz NOT NULL DEFAULT now(),
	updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX chapter_role_chapter_idx ON chapter_role (chapter_id) WHERE ends_at IS NULL;
CREATE INDEX chapter_role_person_idx ON chapter_role (person_id) WHERE ends_at IS NULL;

CREATE TABLE national_admin (
	person_id  uuid PRIMARY KEY REFERENCES person (id),
	granted_by uuid REFERENCES person (id),
	granted_at timestamptz NOT NULL DEFAULT now()
);

-- A person's identity in each tool. external_id is the stable ID (numeric GitHub ID, Slack user ID);
-- external_login is a username or email as last seen, never used to identify anyone.
CREATE TABLE tool_account (
	id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	person_id      uuid NOT NULL REFERENCES person (id),
	tool           tool_name NOT NULL,
	external_id    text,
	external_login text,
	state          text NOT NULL DEFAULT 'unverified'
		CHECK (state IN ('unverified', 'invited', 'accepted', 'confirmed', 'suspended', 'removed')),
	verified_at    timestamptz,
	source_id      uuid UNIQUE,
	imported_at    timestamptz,
	created_at     timestamptz NOT NULL DEFAULT now(),
	updated_at     timestamptz NOT NULL DEFAULT now(),
	UNIQUE (tool, external_id),
	UNIQUE (person_id, tool),
	CHECK (external_id IS NOT NULL OR external_login IS NOT NULL)
);

-- Projects ------------------------------------------------------------------------------------

CREATE TABLE partner (
	id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	name        text NOT NULL UNIQUE,
	website     text,
	source_id   uuid UNIQUE,
	imported_at timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now(),
	updated_at  timestamptz NOT NULL DEFAULT now(),
	deleted_at  timestamptz
);

CREATE TABLE project_role (
	id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	name        text NOT NULL UNIQUE,
	is_lead     boolean NOT NULL DEFAULT false,
	source_id   uuid UNIQUE,
	imported_at timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now(),
	updated_at  timestamptz NOT NULL DEFAULT now()
);

-- A project is one chapter's work for one partner over a period (an "engagement" in the member DB).
CREATE TABLE project (
	id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id     uuid NOT NULL REFERENCES chapter (id),
	partner_id     uuid REFERENCES partner (id),
	name           text NOT NULL,
	slug           text NOT NULL CHECK (slug ~ '^[a-z0-9][a-z0-9-]*$'), -- with the chapter code: umd-rise-dc
	term_id        uuid REFERENCES term (id),
	type           text, -- matched by Notion routes, e.g. new-build, lts
	tags           text[] NOT NULL DEFAULT '{}',
	status         text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'active', 'paused', 'closed')),
	starts_on      date,
	ends_on        date,
	notion_page_id text,
	closed_at      timestamptz,
	source_id      uuid UNIQUE,
	imported_at    timestamptz,
	created_at     timestamptz NOT NULL DEFAULT now(),
	updated_at     timestamptz NOT NULL DEFAULT now(),
	UNIQUE (chapter_id, slug)
);

CREATE TABLE project_member (
	id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	project_id       uuid NOT NULL REFERENCES project (id),
	person_id        uuid NOT NULL REFERENCES person (id),
	project_role_id  uuid REFERENCES project_role (id),
	agreement_status text NOT NULL DEFAULT 'pending' CHECK (agreement_status IN ('pending', 'signed', 'waived')),
	added_by         uuid REFERENCES person (id),
	added_at         timestamptz NOT NULL DEFAULT now(),
	removed_at       timestamptz,
	source_id        uuid UNIQUE,
	imported_at      timestamptz,
	created_at       timestamptz NOT NULL DEFAULT now(),
	updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX project_member_active_idx ON project_member (project_id, person_id) WHERE removed_at IS NULL;

-- Resources and rules -------------------------------------------------------------------------

-- Anything access is granted to: a Slack channel, GitHub team, Notion page, Google group, vault collection.
CREATE TABLE resource (
	id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	tool        tool_name NOT NULL,
	external_id text, -- channel ID, team slug, page ID, group email, collection ID; null until created
	name        text NOT NULL,
	chapter_id  uuid REFERENCES chapter (id),
	tags        text[] NOT NULL DEFAULT '{}',
	managed     text NOT NULL DEFAULT 'portal' CHECK (managed IN ('portal', 'adopted', 'unmanaged')),
	archived_at timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now(),
	updated_at  timestamptz NOT NULL DEFAULT now(),
	UNIQUE (tool, external_id)
);

CREATE TABLE project_resource (
	project_id         uuid NOT NULL REFERENCES project (id),
	resource_id        uuid NOT NULL REFERENCES resource (id),
	audience           text NOT NULL CHECK (audience IN ('team', 'role', 'leads')),
	audience_role_id   uuid REFERENCES project_role (id),
	access_level       text NOT NULL,
	requires_agreement boolean NOT NULL DEFAULT false,
	created_at         timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (project_id, resource_id),
	CHECK ((audience = 'role') = (audience_role_id IS NOT NULL))
);

-- Chapter-level access granted on approval: the chapter channel, chapter GitHub team, Notion teamspace.
CREATE TABLE chapter_resource (
	chapter_id   uuid NOT NULL REFERENCES chapter (id),
	resource_id  uuid NOT NULL REFERENCES resource (id),
	audience     text NOT NULL CHECK (audience IN ('members', 'leads')),
	access_level text NOT NULL,
	created_at   timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (chapter_id, resource_id)
);

-- "When [who] -> add to [projects] . [resources]". Rules only add access. chapter_id null = national default.
CREATE TABLE rule (
	id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id   uuid REFERENCES chapter (id),
	name         text NOT NULL,
	enabled      boolean NOT NULL DEFAULT true,
	who          jsonb NOT NULL, -- {"chapterRoles": [...], "projectRoles": [...], "people": [...]}
	projects     jsonb NOT NULL, -- {"all": true} | {"ids": [...]} | {"tags": [...]}
	resources    jsonb NOT NULL, -- {"tags": [...]} | {"ids": [...]}
	tools        text[] NOT NULL DEFAULT '{}', -- empty = every tool
	access_level text NOT NULL,
	created_by   uuid REFERENCES person (id),
	created_at   timestamptz NOT NULL DEFAULT now(),
	updated_at   timestamptz NOT NULL DEFAULT now()
);

-- Agreements ----------------------------------------------------------------------------------

CREATE TABLE agreement_template (
	id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	kind                  text NOT NULL CHECK (kind IN ('volunteer', 'code_of_conduct', 'project')),
	name                  text NOT NULL,
	documenso_template_id text NOT NULL,
	version               integer NOT NULL,
	active                boolean NOT NULL DEFAULT true,
	created_at            timestamptz NOT NULL DEFAULT now(),
	UNIQUE (documenso_template_id, version)
);

CREATE TABLE agreement (
	id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	person_id             uuid NOT NULL REFERENCES person (id),
	project_id            uuid REFERENCES project (id), -- null for registration agreements
	template_id           uuid NOT NULL REFERENCES agreement_template (id),
	documenso_document_id text UNIQUE,
	status                text NOT NULL DEFAULT 'draft'
		CHECK (status IN ('draft', 'sent', 'signed', 'declined', 'expired', 'voided')),
	sent_at               timestamptz,
	signed_at             timestamptz,
	pdf_location          text,
	reminders_sent        integer NOT NULL DEFAULT 0,
	last_reminder_at      timestamptz,
	created_at            timestamptz NOT NULL DEFAULT now(),
	updated_at            timestamptz NOT NULL DEFAULT now()
);

-- Drafts and releases -------------------------------------------------------------------------

CREATE TABLE changeset (
	id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id    uuid REFERENCES chapter (id), -- null = national
	name          text NOT NULL,
	status        text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'scheduled', 'released', 'discarded')),
	created_by    uuid NOT NULL REFERENCES person (id),
	scheduled_for timestamptz,
	released_at   timestamptz,
	released_by   uuid REFERENCES person (id),
	discarded_at  timestamptz,
	created_at    timestamptz NOT NULL DEFAULT now(),
	updated_at    timestamptz NOT NULL DEFAULT now(),
	CHECK (status <> 'scheduled' OR scheduled_for IS NOT NULL)
);

CREATE TABLE change (
	id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	changeset_id uuid NOT NULL REFERENCES changeset (id),
	position     integer NOT NULL,
	operation    text NOT NULL, -- e.g. add_member, close_project, attach_resource
	target_type  text NOT NULL,
	target_id    uuid,
	payload      jsonb NOT NULL DEFAULT '{}',
	created_at   timestamptz NOT NULL DEFAULT now(),
	UNIQUE (changeset_id, position)
);

-- Sync ----------------------------------------------------------------------------------------

CREATE TABLE tool_setting (
	tool              tool_name PRIMARY KEY,
	enabled           boolean NOT NULL DEFAULT true,  -- kill switch
	dry_run           boolean NOT NULL DEFAULT true,  -- every tool starts in dry run
	max_removals      integer NOT NULL DEFAULT 25,    -- blast-radius limit per run
	max_removal_ratio numeric(4, 3) NOT NULL DEFAULT 0.2,
	paused_by         uuid REFERENCES person (id),
	pause_reason      text,
	updated_at        timestamptz NOT NULL DEFAULT now()
);
INSERT INTO tool_setting (tool) VALUES ('google'), ('github'), ('slack'), ('notion'), ('vaultwarden'), ('documenso');

CREATE TABLE sync_run (
	id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	tool            tool_name, -- null = every tool
	mode            text NOT NULL CHECK (mode IN ('dry_run', 'apply')),
	trigger         text NOT NULL CHECK (trigger IN ('schedule', 'release', 'manual')),
	status          text NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'completed', 'paused', 'failed')),
	started_at      timestamptz NOT NULL DEFAULT now(),
	finished_at     timestamptz,
	planned_adds    integer NOT NULL DEFAULT 0,
	planned_removes integer NOT NULL DEFAULT 0,
	applied_adds    integer NOT NULL DEFAULT 0,
	applied_removes integer NOT NULL DEFAULT 0,
	confirmed_by    uuid REFERENCES person (id), -- national confirmation after a blast-radius pause
	error           text
);

CREATE TABLE sync_record (
	person_id            uuid NOT NULL REFERENCES person (id),
	resource_id          uuid NOT NULL REFERENCES resource (id),
	desired              text NOT NULL CHECK (desired IN ('present', 'absent')),
	desired_access_level text,
	reasons              jsonb NOT NULL DEFAULT '[]', -- why the resolver wants this: project, rule, default, agreement
	actual               text NOT NULL DEFAULT 'unknown' CHECK (actual IN ('present', 'absent', 'unknown')),
	status               text NOT NULL DEFAULT 'pending'
		CHECK (status IN ('in_sync', 'pending', 'waiting_on_member', 'manual_task', 'failed', 'dead_letter')),
	attempts             integer NOT NULL DEFAULT 0,
	last_attempt_at      timestamptz,
	next_retry_at        timestamptz,
	last_error           text,
	last_run_id          uuid REFERENCES sync_run (id),
	updated_at           timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (person_id, resource_id)
);

-- Manual steps where a tool has no API on our plan (Notion membership, Slack deactivation).
CREATE TABLE admin_task (
	id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	tool             tool_name NOT NULL,
	action           text NOT NULL,
	description      text NOT NULL, -- written as an action: "Add Jane Doe to Notion group umd-rise-dc"
	person_id        uuid REFERENCES person (id),
	resource_id      uuid REFERENCES resource (id),
	status           text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'done', 'cancelled')),
	reminders_sent   integer NOT NULL DEFAULT 0,
	done_by          uuid REFERENCES person (id),
	done_at          timestamptz,
	cancelled_reason text,
	created_at       timestamptz NOT NULL DEFAULT now(),
	updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX admin_task_open_idx ON admin_task (tool) WHERE status = 'open';

-- Audit and imports ---------------------------------------------------------------------------

CREATE TABLE audit_event (
	id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	at              timestamptz NOT NULL DEFAULT now(),
	actor_type      text NOT NULL CHECK (actor_type IN ('person', 'sync', 'system', 'import')),
	actor_person_id uuid REFERENCES person (id),
	action          text NOT NULL,
	target_type     text NOT NULL,
	target_id       uuid,
	chapter_id      uuid REFERENCES chapter (id),
	changeset_id    uuid REFERENCES changeset (id),
	before          jsonb,
	after           jsonb,
	CHECK ((actor_type = 'person') = (actor_person_id IS NOT NULL))
);
CREATE INDEX audit_event_target_idx ON audit_event (target_type, target_id);
CREATE INDEX audit_event_chapter_idx ON audit_event (chapter_id, at);

CREATE TABLE import_run (
	id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	source      text NOT NULL DEFAULT 'member_db',
	status      text NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'completed', 'failed')),
	started_at  timestamptz NOT NULL DEFAULT now(),
	finished_at timestamptz,
	counts      jsonb,
	issues      jsonb,
	error       text
);

-- updated_at triggers -------------------------------------------------------------------------

CREATE TRIGGER institution_updated_at BEFORE UPDATE ON institution FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER term_updated_at BEFORE UPDATE ON term FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER chapter_updated_at BEFORE UPDATE ON chapter FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER person_updated_at BEFORE UPDATE ON person FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER chapter_role_updated_at BEFORE UPDATE ON chapter_role FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tool_account_updated_at BEFORE UPDATE ON tool_account FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER partner_updated_at BEFORE UPDATE ON partner FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER project_role_updated_at BEFORE UPDATE ON project_role FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER project_updated_at BEFORE UPDATE ON project FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER project_member_updated_at BEFORE UPDATE ON project_member FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER resource_updated_at BEFORE UPDATE ON resource FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER rule_updated_at BEFORE UPDATE ON rule FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER agreement_updated_at BEFORE UPDATE ON agreement FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER changeset_updated_at BEFORE UPDATE ON changeset FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tool_setting_updated_at BEFORE UPDATE ON tool_setting FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER sync_record_updated_at BEFORE UPDATE ON sync_record FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER admin_task_updated_at BEFORE UPDATE ON admin_task FOR EACH ROW EXECUTE FUNCTION set_updated_at();
