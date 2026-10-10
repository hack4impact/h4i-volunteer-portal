package org.hack4impact.portal

import org.jooq.DSLContext

/**
 * Empties every portal table, for tests that need a clean database. TRUNCATE ... CASCADE also empties
 * tool_setting (its paused_by references person), so the default settings V1 seeds are put back.
 */
fun DSLContext.emptyPortalTables() {
	val keep = setOf("flyway_schema_history")
	val tables = fetch("SELECT tablename FROM pg_tables WHERE schemaname = 'portal'").map { it.get(0, String::class.java) } - keep
	execute("TRUNCATE " + tables.joinToString { "portal.\"$it\"" } + " CASCADE")
	execute("INSERT INTO portal.tool_setting (tool) VALUES ('google'), ('github'), ('slack'), ('notion'), ('vaultwarden'), ('documenso') ON CONFLICT DO NOTHING")
}
