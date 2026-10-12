-- Chapters that run their own application process turn the portal's off (decision 116). Roster invites and claim
-- links still work; the join page points to application_url when there is one.
ALTER TABLE chapter
	ADD COLUMN applications_open boolean NOT NULL DEFAULT true,
	ADD COLUMN application_url   text;
