-- Sync engine, dry run (build plan step 6).

-- Every planned change and finding of a sync run: the dry-run report, and later the record of what was applied.
CREATE TABLE sync_change (
	id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	run_id       uuid NOT NULL REFERENCES sync_run (id),
	kind         text NOT NULL CHECK (kind IN ('add', 'change', 'remove', 'drift', 'unmatched_account', 'missing_resource')),
	person_id    uuid REFERENCES person (id),
	resource_id  uuid REFERENCES resource (id),
	account_id   text,          -- the tool's account ID, for unmatched accounts and drift
	from_access  text,
	to_access    text,
	reasons      jsonb NOT NULL DEFAULT '[]', -- why the portal wants it (adds, changes) or wanted it (removals)
	created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX sync_change_run_idx ON sync_change (run_id, kind);

-- Set when a write sync (step 9) actually applied the grant. Only applied grants can ever be removed
-- (wiki decision 52): the portal never takes away access it didn't give.
ALTER TABLE sync_record ADD COLUMN applied_at timestamptz;

-- Spring Modulith's event publication registry (the outbox, wiki decision 33), schema v2 for PostgreSQL,
-- as shipped in spring-modulith-events-jdbc 2.1.1. Flyway owns it instead of Modulith's schema initializer.
CREATE TABLE event_publication (
	id                     uuid NOT NULL PRIMARY KEY,
	listener_id            text NOT NULL,
	event_type             text NOT NULL,
	serialized_event       text NOT NULL,
	publication_date       timestamptz NOT NULL,
	completion_date        timestamptz,
	status                 text,
	completion_attempts    int,
	last_resubmission_date timestamptz
);
CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
