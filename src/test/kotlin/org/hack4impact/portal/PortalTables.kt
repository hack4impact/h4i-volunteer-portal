package org.hack4impact.portal

import org.jooq.DSLContext

/** Empties every portal table except the seeded tool settings, for tests that need a clean database. */
fun DSLContext.emptyPortalTables() {
	val keep = setOf("tool_setting", "flyway_schema_history")
	val tables = fetch("SELECT tablename FROM pg_tables WHERE schemaname = 'portal'").map { it.get(0, String::class.java) } - keep
	execute("TRUNCATE " + tables.joinToString { "portal.\"$it\"" } + " CASCADE")
}
