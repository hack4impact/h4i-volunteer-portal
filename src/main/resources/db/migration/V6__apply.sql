-- Write adapters and apply mode (build plan step 9).

-- What happened to each planned change when a run applied it. Null in dry runs.
--   applied: done   invited: the tool sent an invite the person still has to accept   waiting: the person has no
--   account in the tool yet   held: over the blast-radius limit, waiting for national   failed: will be retried
--   dead_letter: failed too often; a person has to look   skipped: not due for a retry yet
ALTER TABLE sync_change
	ADD COLUMN outcome text CHECK (outcome IN ('applied', 'invited', 'waiting', 'held', 'failed', 'dead_letter', 'skipped')),
	ADD COLUMN error text;

-- A blast-radius hold asks national to confirm that run's removals.
ALTER TABLE admin_task ADD COLUMN run_id uuid REFERENCES sync_run (id);
