package org.hack4impact.portal.memberimport

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** A fake national member DB (schema recreated from the real export, fake rows), started once per test run. */
object MemberDbFixture {
	val container: PostgreSQLContainer by lazy {
		PostgreSQLContainer(DockerImageName.parse("postgres:17")).also {
			it.start()
			ResourceDatabasePopulator(ClassPathResource("memberdb/schema.sql"), ClassPathResource("memberdb/seed.sql"))
				.execute(DriverManagerDataSource(it.jdbcUrl, it.username, it.password))
		}
	}

	val settings get() = MemberImportProperties.MemberDb(container.jdbcUrl, container.username, container.password)
}
