-- Roster invites and claim links are emailed from the outbox after the upload commits, not in the lead's request
-- (second review M4). The email carries a new token whose hash replaces the one stored at upload, so no usable
-- token is ever stored. emailed_at marks the ones already sent, so a retry after a crash sends only what's left.
ALTER TABLE roster_invite ADD COLUMN emailed_at timestamptz;
ALTER TABLE claim_link ADD COLUMN emailed_at timestamptz;

-- Everything before this was emailed in the request that created it.
UPDATE roster_invite SET emailed_at = created_at;
UPDATE claim_link SET emailed_at = created_at;

-- A portal-made resource whose creation fails is retried with backoff, like a member's change, and after five
-- failures waits on the admin queue for national instead of being tried on every run (second review L3).
ALTER TABLE resource ADD COLUMN create_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE resource ADD COLUMN create_retry_at timestamptz;

-- A run the app stopped in the middle of is marked failed at the next start, and every tool is synced once to cover
-- requests that were waiting on it (second review L5).
ALTER TABLE sync_run DROP CONSTRAINT sync_run_trigger_check;
ALTER TABLE sync_run ADD CONSTRAINT sync_run_trigger_check CHECK (trigger IN ('schedule', 'release', 'manual', 'agreement', 'rule', 'change', 'recovery'));
