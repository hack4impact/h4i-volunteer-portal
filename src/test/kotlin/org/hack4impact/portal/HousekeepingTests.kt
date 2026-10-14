package org.hack4impact.portal

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.Test
import kotlin.test.assertEquals

/** Second review (test gaps): the nightly deletes run against the real schema, and keep what they should. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["portal.sync.on-request=false"])
class HousekeepingTests(@Autowired private val dsl: DSLContext, @Autowired private val housekeeping: Housekeeping) {
	@BeforeEach
	fun empty() = dsl.emptyPortalTables()

	private fun count(sql: String) = dsl.fetchValue(sql) as Long

	@Test
	fun `old dry-run plans, run notes, finished events and spent codes and sessions go, while applied changes and recent rows stay`() {
		dsl.execute(
			"""
			INSERT INTO portal.sync_run (id, tool, mode, trigger, status, started_at) VALUES
			  ('00000000-0000-0000-0000-000000000001', 'slack', 'dry_run', 'manual', 'completed', now() - interval '31 days'),
			  ('00000000-0000-0000-0000-000000000002', 'slack', 'apply', 'manual', 'completed', now() - interval '31 days'),
			  ('00000000-0000-0000-0000-000000000003', 'slack', 'dry_run', 'manual', 'completed', now() - interval '29 days');
			INSERT INTO portal.sync_change (run_id, kind, outcome) VALUES
			  ('00000000-0000-0000-0000-000000000001', 'add', null),      -- an old dry run's plan: goes
			  ('00000000-0000-0000-0000-000000000002', 'add', 'applied'), -- an old applied change: stays
			  ('00000000-0000-0000-0000-000000000002', 'drift', null),    -- an old run's note: goes
			  ('00000000-0000-0000-0000-000000000003', 'add', null);      -- a recent dry run: stays
			INSERT INTO portal.event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date) VALUES
			  (gen_random_uuid(), 'l', 't', '{}', now() - interval '9 days', now() - interval '8 days'), -- finished long ago: goes
			  (gen_random_uuid(), 'l', 't', '{}', now() - interval '9 days', null),                      -- never finished: stays
			  (gen_random_uuid(), 'l', 't', '{}', now() - interval '1 day', now() - interval '1 day');    -- finished recently: stays
			INSERT INTO portal.chapter (id, code, name) VALUES ('00000000-0000-0000-0000-0000000000c1', 'umd', 'UMD');
			INSERT INTO portal.email_code (chapter_id, email, code_hash, expires_at) VALUES
			  ('00000000-0000-0000-0000-0000000000c1', 'a@umd.edu', 'x', now() - interval '2 days'),
			  ('00000000-0000-0000-0000-0000000000c1', 'b@umd.edu', 'y', now() + interval '5 minutes');
			INSERT INTO portal.registration (id, chapter_id, kind) VALUES ('00000000-0000-0000-0000-0000000000a1', '00000000-0000-0000-0000-0000000000c1', 'new');
			INSERT INTO portal.applicant_session (registration_id, token_hash, expires_at, revoked_at) VALUES
			  ('00000000-0000-0000-0000-0000000000a1', 'expired', now() - interval '2 days', null),
			  ('00000000-0000-0000-0000-0000000000a1', 'revoked', now() + interval '2 days', now() - interval '2 days'),
			  ('00000000-0000-0000-0000-0000000000a1', 'live', now() + interval '2 days', null);
			""".trimIndent(),
		)

		assertEquals(
			mapOf("syncNotes" to 2, "finishedEvents" to 1, "emailCodes" to 1, "applicantSessions" to 2),
			housekeeping.clean(),
		)
		assertEquals(2L, count("SELECT count(*) FROM portal.sync_change"))
		assertEquals(1L, count("SELECT count(*) FROM portal.sync_change WHERE outcome = 'applied'"))
		assertEquals(2L, count("SELECT count(*) FROM portal.event_publication"))
		assertEquals(1L, count("SELECT count(*) FROM portal.email_code"))
		assertEquals(1L, count("SELECT count(*) FROM portal.applicant_session WHERE token_hash = 'live'"))
		assertEquals(mapOf("syncNotes" to 0, "finishedEvents" to 0, "emailCodes" to 0, "applicantSessions" to 0), housekeeping.clean()) // nothing twice
	}
}
