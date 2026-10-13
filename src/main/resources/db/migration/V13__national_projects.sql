-- National's own projects (wiki decision 141): one chapter of kind 'national' holds them. Its projects take anyone in
-- the org, its resources aren't limited to one chapter's members, and nobody applies to it.
ALTER TABLE chapter
	ADD COLUMN kind text NOT NULL DEFAULT 'chapter' CHECK (kind IN ('chapter', 'national'));

CREATE UNIQUE INDEX chapter_one_national ON chapter (kind) WHERE kind = 'national';

INSERT INTO chapter (code, name, kind, applications_open)
VALUES ('national', 'Hack4Impact National', 'national', false)
ON CONFLICT DO NOTHING;
