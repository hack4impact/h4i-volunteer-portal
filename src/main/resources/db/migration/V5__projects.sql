-- Projects UI (build plan step 8): standard resources for new projects, Notion routes, and planned resource creation.

-- The standard resources a new project gets (PRD: "creating a project auto-creates its standard resources from a
-- chapter template"). chapter_id null = the national template, used by every chapter without rows of its own.
-- name_pattern placeholders: {chapter} (chapter code) and {project} (project slug).
CREATE TABLE resource_template (
	id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id         uuid REFERENCES chapter (id),
	position           integer NOT NULL DEFAULT 0,
	tool               tool_name NOT NULL,
	name_pattern       text NOT NULL,
	audience           text NOT NULL DEFAULT 'team' CHECK (audience IN ('team', 'leads')),
	access_level       text NOT NULL CHECK (access_level IN ('read', 'write', 'admin')),
	requires_agreement boolean NOT NULL DEFAULT false,
	tags               text[] NOT NULL DEFAULT '{}',
	created_at         timestamptz NOT NULL DEFAULT now()
);

-- National template (wiki decision 82): a private channel and a Google group for the team; the GitHub team and the
-- vault collection only after the project agreement is signed (PRD: repos and vault collections are gated).
INSERT INTO resource_template (position, tool, name_pattern, access_level, requires_agreement, tags) VALUES
	(1, 'slack', '{chapter}-{project}', 'write', false, '{main}'),
	(2, 'google', '{chapter}-{project}@hack4impact.org', 'write', false, '{main}'),
	(3, 'github', '{chapter}-{project}', 'write', true, '{eng}'),
	(4, 'vaultwarden', '{chapter}-{project}', 'write', true, '{eng}');

-- Notion routes (PRD: chapter settings). Ordered rules from a project's tag or type to a parent page; first match
-- wins, else the chapter's default parent. Page IDs, not paths, are stored.
ALTER TABLE chapter
	ADD COLUMN notion_default_parent_id text,
	ADD COLUMN notion_template_page_id  text,
	ADD COLUMN notion_title_pattern     text NOT NULL DEFAULT '{project} ({semester})';

CREATE TABLE notion_route (
	id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	chapter_id     uuid NOT NULL REFERENCES chapter (id),
	position       integer NOT NULL,
	match_kind     text NOT NULL CHECK (match_kind IN ('tag', 'type')),
	match_value    text NOT NULL,
	parent_page_id text NOT NULL,
	created_at     timestamptz NOT NULL DEFAULT now(),
	UNIQUE (chapter_id, position)
);

-- The latest check of a Notion page the routes point at: its path for the preview, or why it can't be reached.
CREATE TABLE notion_page_check (
	page_id    text PRIMARY KEY,
	path       text,
	error      text,
	checked_at timestamptz NOT NULL DEFAULT now(),
	CHECK ((path IS NULL) <> (error IS NULL))
);

-- A dry run now also plans creating resources that exist only in the portal so far (no external ID yet).
ALTER TABLE sync_change DROP CONSTRAINT sync_change_kind_check;
ALTER TABLE sync_change ADD CONSTRAINT sync_change_kind_check
	CHECK (kind IN ('add', 'change', 'remove', 'drift', 'unmatched_account', 'missing_resource', 'create_resource'));
