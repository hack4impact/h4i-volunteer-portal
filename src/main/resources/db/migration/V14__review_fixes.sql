-- Fixes from the 2026-10-13 code review (wiki: code-review-2026-10-13).

-- H1, H4: syncs requested by a rule change or a direct project change.
ALTER TABLE sync_run DROP CONSTRAINT sync_run_trigger_check;
ALTER TABLE sync_run ADD CONSTRAINT sync_run_trigger_check CHECK (trigger IN ('schedule', 'release', 'manual', 'agreement', 'rule', 'change'));

-- H3: the access level the portal itself last applied, so it never lowers access it didn't give (a group owner,
-- a team maintainer). Dry runs keep rewriting desired_access_level, so it can't serve.
ALTER TABLE sync_record ADD COLUMN applied_access_level text;
UPDATE sync_record SET applied_access_level = desired_access_level WHERE applied_at IS NOT NULL;

-- M6: when an open agreement was last read back from Documenso, so every pass checks the longest-unchecked ones.
ALTER TABLE agreement ADD COLUMN checked_at timestamptz;
CREATE INDEX agreement_open_checked_idx ON agreement (checked_at NULLS FIRST) WHERE status = 'sent';
