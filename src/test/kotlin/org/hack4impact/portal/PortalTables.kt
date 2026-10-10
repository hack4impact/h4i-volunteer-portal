package org.hack4impact.portal

import org.hack4impact.portal.db.tables.references.RESOURCE_TEMPLATE
import org.jooq.DSLContext

/**
 * Empties every portal table, for tests that need a clean database. TRUNCATE ... CASCADE also empties
 * tool_setting (its paused_by references person) and the national resource template (V5), so what the
 * migrations seed is put back.
 */
fun DSLContext.emptyPortalTables() {
	val keep = setOf("flyway_schema_history")
	val tables = fetch("SELECT tablename FROM pg_tables WHERE schemaname = 'portal'").map { it.get(0, String::class.java) } - keep
	execute("TRUNCATE " + tables.joinToString { "portal.\"$it\"" } + " CASCADE")
	execute("INSERT INTO portal.tool_setting (tool) VALUES ('google'), ('github'), ('slack'), ('notion'), ('vaultwarden'), ('documenso') ON CONFLICT DO NOTHING")
	val national = listOf(
		listOf("slack", "{chapter}-{project}", false, "main"),
		listOf("google", "{chapter}-{project}@hack4impact.org", false, "main"),
		listOf("github", "{chapter}-{project}", true, "eng"),
		listOf("vaultwarden", "{chapter}-{project}", true, "eng"),
	)
	national.forEachIndexed { i, (tool, pattern, gated, tag) ->
		insertInto(RESOURCE_TEMPLATE)
			.set(RESOURCE_TEMPLATE.POSITION, i + 1).set(RESOURCE_TEMPLATE.TOOL, tool as String).set(RESOURCE_TEMPLATE.NAME_PATTERN, pattern as String)
			.set(RESOURCE_TEMPLATE.ACCESS_LEVEL, "write").set(RESOURCE_TEMPLATE.REQUIRES_AGREEMENT, gated as Boolean).set(RESOURCE_TEMPLATE.TAGS, arrayOf(tag as String))
			.execute()
	}
}
