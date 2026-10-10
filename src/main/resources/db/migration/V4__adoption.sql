-- Adoption scan (build plan step 7): what already exists in each tool, how it maps to chapters and projects,
-- and who is in it today. Read-only towards the tools; nothing here changes access.

-- Other prefixes a chapter's resources use besides its code, e.g. a chapter coded "umd" whose channels start "terps-".
ALTER TABLE chapter ADD COLUMN name_prefixes text[] NOT NULL DEFAULT '{}';

-- One discovery pass over one tool.
CREATE TABLE discovery_scan (
	id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	tool            tool_name NOT NULL,
	status          text NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'completed', 'failed')),
	chapters        text[] NOT NULL DEFAULT '{}', -- chapter codes whose resources' members were snapshotted
	resources_found integer NOT NULL DEFAULT 0,
	members_read    integer NOT NULL DEFAULT 0,
	error           text,
	started_at      timestamptz NOT NULL DEFAULT now(),
	finished_at     timestamptz
);
CREATE INDEX discovery_scan_tool_idx ON discovery_scan (tool, started_at DESC);

-- Every channel, team or group a scan has seen, and what it's matched or linked to.
--   unmatched: no chapter found          matched: matched by the naming convention
--   linked:    a lead or national linked it by hand (or confirmed the match)
--   unmanaged: a lead chose to leave it alone   adopted: managed by the portal (resource_id set; drafts, step 12)
CREATE TABLE discovered_resource (
	id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	tool           tool_name NOT NULL,
	external_id    text NOT NULL,
	name           text NOT NULL,
	archived       boolean NOT NULL DEFAULT false,
	state          text NOT NULL DEFAULT 'unmatched' CHECK (state IN ('unmatched', 'matched', 'linked', 'unmanaged', 'adopted')),
	chapter_id     uuid REFERENCES chapter (id),
	project_id     uuid REFERENCES project (id),
	-- Who it would be for once adopted. Null = the chapter is known but a lead has to decide.
	target         text CHECK (target IN ('chapter_members', 'chapter_leads', 'project_team')),
	match_method   text CHECK (match_method IN ('convention', 'manual')),
	suggested_slug text, -- the project slug the name implies when no such project exists yet
	resource_id    uuid REFERENCES resource (id),
	linked_by      uuid REFERENCES person (id),
	linked_at      timestamptz,
	first_seen_at  timestamptz NOT NULL DEFAULT now(),
	last_seen_at   timestamptz NOT NULL DEFAULT now(),
	gone_at        timestamptz, -- the latest scan didn't see it
	last_scan_id   uuid REFERENCES discovery_scan (id),
	snapshot_scan_id uuid REFERENCES discovery_scan (id), -- the latest scan that read its members
	UNIQUE (tool, external_id),
	CHECK (target IS NULL OR chapter_id IS NOT NULL),
	CHECK ((target = 'project_team') = (project_id IS NOT NULL)),
	CHECK (state <> 'unmatched' OR chapter_id IS NULL)
);
CREATE INDEX discovered_resource_chapter_idx ON discovered_resource (chapter_id);

-- Who was in a matched or linked resource when a scan read it: the grandfather snapshot. Raw facts only;
-- the adoption report decides expected, grandfathered and unknown against the current desired state.
CREATE TABLE adoption_snapshot (
	id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	discovered_resource_id uuid NOT NULL REFERENCES discovered_resource (id) ON DELETE CASCADE,
	scan_id                uuid NOT NULL REFERENCES discovery_scan (id),
	account_id             text NOT NULL,
	login                  text, -- the tool's login or email for the account, when it shows one
	person_id              uuid REFERENCES person (id),
	matched_by             text CHECK (matched_by IN ('id', 'login', 'email')),
	access                 text NOT NULL CHECK (access IN ('read', 'write', 'admin')),
	snapshotted_at         timestamptz NOT NULL DEFAULT now(),
	UNIQUE (discovered_resource_id, scan_id, account_id),
	CHECK ((person_id IS NULL) = (matched_by IS NULL))
);
CREATE INDEX adoption_snapshot_scan_idx ON adoption_snapshot (scan_id);
