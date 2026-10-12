-- Agreements through Documenso (build plan step 11). agreement_template.documenso_template_id holds the template
-- envelope's ID; agreement.documenso_document_id the envelope made from it.

-- Registration agreements (volunteer agreement, code of conduct) are signed before approval, when there's no person
-- yet: they belong to the registration until it's approved.
ALTER TABLE agreement
	ALTER COLUMN person_id DROP NOT NULL,
	ADD COLUMN registration_id  uuid REFERENCES registration (id),
	ADD COLUMN lead_notified_at timestamptz,
	ADD COLUMN decline_reason   text,
	-- The last failure sending or reading it, in words (cleared when it works again).
	ADD COLUMN error            text,
	ADD CONSTRAINT agreement_owner CHECK (person_id IS NOT NULL OR registration_id IS NOT NULL);

-- One live agreement per person and project, and per registration and template; a signed one counts again if the
-- person rejoins the project.
CREATE UNIQUE INDEX agreement_project_idx ON agreement (person_id, project_id)
	WHERE project_id IS NOT NULL AND status IN ('draft', 'sent', 'signed');
CREATE UNIQUE INDEX agreement_registration_idx ON agreement (registration_id, template_id)
	WHERE registration_id IS NOT NULL AND status IN ('draft', 'sent', 'signed');
CREATE INDEX agreement_open_idx ON agreement (status) WHERE status = 'sent';

-- Signed PDFs, archived in the portal's own database (decision 119); one row per document in the envelope.
CREATE TABLE agreement_pdf (
	agreement_id uuid NOT NULL REFERENCES agreement (id),
	position     integer NOT NULL,
	content      bytea NOT NULL,
	sha256       text NOT NULL,
	created_at   timestamptz NOT NULL DEFAULT now(),
	PRIMARY KEY (agreement_id, position)
);

-- At most one active template per kind: the one new agreements are made from.
CREATE UNIQUE INDEX agreement_template_active_idx ON agreement_template (kind) WHERE active;

-- A signature asks for a sync of its own kind, so the history says why access changed.
ALTER TABLE sync_run DROP CONSTRAINT sync_run_trigger_check;
ALTER TABLE sync_run ADD CONSTRAINT sync_run_trigger_check CHECK (trigger IN ('schedule', 'release', 'manual', 'agreement'));
