-- Drafts and releases (build plan step 12). A changeset's changes are project operations, replayed in order: in a
-- rolled-back transaction to show the draft and its preview, for real in one transaction to release it.

ALTER TABLE change
	ADD COLUMN created_by uuid REFERENCES person (id),
	-- The change in words ("Add Ada Lovelace to RISE DC"), written when it was recorded.
	ADD COLUMN summary    text NOT NULL DEFAULT '';

ALTER TABLE changeset
	ADD COLUMN scheduled_by  uuid REFERENCES person (id),
	-- Why a scheduled release didn't happen (it went back to being a draft).
	ADD COLUMN release_error text,
	-- What the release did, kept for the audit trail: counts and each person's access changes.
	ADD COLUMN released_summary jsonb;

CREATE INDEX changeset_chapter_idx ON changeset (chapter_id, status);
CREATE INDEX changeset_due_idx ON changeset (scheduled_for) WHERE status = 'scheduled';
