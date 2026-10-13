-- Tool plugins (wiki: tool-plugins): which tools exist is the app's tool registry, not a fixed list here. The domain
-- keeps tool names well-formed; the app checks them against the registry and adds each registered tool's
-- tool_setting row (in dry run) at startup.
ALTER DOMAIN tool_name DROP CONSTRAINT tool_name_check;
ALTER DOMAIN tool_name ADD CONSTRAINT tool_name_check CHECK (VALUE ~ '^[a-z][a-z0-9_]*$');
