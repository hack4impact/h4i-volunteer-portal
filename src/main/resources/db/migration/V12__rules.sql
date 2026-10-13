-- Automation rules editor (build plan step 14). Rules are soft-deleted so sync records and the audit log keep
-- pointing at the rule that granted access; the JSON shapes of who/projects/resources stay as V1 defined them.
ALTER TABLE rule
	ADD COLUMN description text,
	ADD COLUMN updated_by  uuid REFERENCES person (id),
	ADD COLUMN deleted_at  timestamptz;

CREATE INDEX rule_chapter_idx ON rule (chapter_id) WHERE deleted_at IS NULL;
