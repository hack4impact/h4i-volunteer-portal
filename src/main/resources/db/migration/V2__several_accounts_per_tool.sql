-- A person can have several accounts in one tool: the member DB has 122 people with 2-3 Slack user IDs
-- and 180 with 2-4 Notion user IDs, most likely from different workspaces. Keep them all; the adapters
-- (step 4) decide which account belongs to the workspace the portal manages. (Wiki decision 45.)
ALTER TABLE tool_account DROP CONSTRAINT tool_account_person_id_tool_key;
CREATE INDEX tool_account_person_tool_idx ON tool_account (person_id, tool);
