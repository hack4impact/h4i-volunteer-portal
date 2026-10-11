-- Step 9: archiving closed projects' channels, and the admin queue for changes with no API (Notion membership).

ALTER TABLE sync_change DROP CONSTRAINT sync_change_kind_check;
ALTER TABLE sync_change ADD CONSTRAINT sync_change_kind_check
	CHECK (kind IN ('add', 'change', 'remove', 'drift', 'unmatched_account', 'missing_resource', 'create_resource', 'archive_resource'));

-- queued: written to the admin queue for a person to do by hand (Notion membership).
ALTER TABLE sync_change DROP CONSTRAINT sync_change_outcome_check;
ALTER TABLE sync_change ADD CONSTRAINT sync_change_outcome_check
	CHECK (outcome IN ('applied', 'invited', 'waiting', 'queued', 'held', 'failed', 'dead_letter', 'skipped'));

-- Admin-queue tasks for one person's access to one resource: add_member or remove_member. At most one open task
-- per person, resource and action.
CREATE UNIQUE INDEX admin_task_open_member_idx ON admin_task (tool, action, person_id, resource_id) WHERE status = 'open' AND person_id IS NOT NULL;

-- When a closed project's Notion page was moved to the trash.
ALTER TABLE project ADD COLUMN notion_page_archived_at timestamptz;
