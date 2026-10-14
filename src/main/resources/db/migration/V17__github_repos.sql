-- Project GitHub repositories (wiki: github-repos, decision 147): an add-on a lead chooses on a project's GitHub
-- team, created empty or from a template repository, or an existing repository linked. Access goes through the team.
CREATE TABLE github_repo (
	id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
	resource_id  uuid NOT NULL REFERENCES resource (id), -- the GitHub team it belongs to
	name         text NOT NULL,                          -- the repository's name in the org
	source       text NOT NULL CHECK (source IN ('empty', 'template', 'linked')),
	template     text,                                   -- the template repository it starts from (source = template)
	private      boolean NOT NULL DEFAULT false,         -- public unless the lead chose private (Q44)
	permission   text NOT NULL CHECK (permission IN ('pull', 'push', 'maintain')), -- what the team gets on it
	github_id    bigint,                                 -- GitHub's repository ID, once created or linked
	--   pending → ready (created or linked, team given access) | failed (after 5 tries, or refused)
	--   ready → forgetting (the lead removed it: the team's access is taken away, the repository stays) → deleted
	state        text NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'ready', 'failed', 'forgetting')),
	last_error   text,
	attempts     integer NOT NULL DEFAULT 0,
	retry_at     timestamptz,
	requested_by uuid REFERENCES person (id),
	created_at   timestamptz NOT NULL DEFAULT now(),
	updated_at   timestamptz NOT NULL DEFAULT now(),
	CHECK ((source = 'template') = (template IS NOT NULL))
);
-- One repository is the portal's on one team at a time (a lead can't give their team another project's code).
CREATE UNIQUE INDEX github_repo_name_idx ON github_repo (lower(name));
CREATE INDEX github_repo_resource_idx ON github_repo (resource_id);
CREATE TRIGGER github_repo_updated_at BEFORE UPDATE ON github_repo FOR EACH ROW EXECUTE FUNCTION set_updated_at();
