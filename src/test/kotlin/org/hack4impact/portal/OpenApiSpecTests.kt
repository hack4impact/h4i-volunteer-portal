package org.hack4impact.portal

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The web app's TypeScript types are generated from web/openapi.json, so it must match the real API.
 * After changing an API: `./gradlew test --tests '*OpenApiSpecTests' -PupdateApiSpec`, then `npm run gen:api` in web/.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTests(@Autowired private val mvc: MockMvc) {
	private val committed = Path.of("web/openapi.json")

	@Test
	fun `web openapi json matches the live API`() {
		val live = mvc.get("/v3/api-docs") { with(oidcLogin()) }.andReturn().response.contentAsString
		val mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
		// Stable output: no server URL (it's the test's random host), pretty-printed, trailing newline.
		val tree = mapper.readTree(live).apply { (this as tools.jackson.databind.node.ObjectNode).remove("servers") }
		val pretty = mapper.writeValueAsString(tree) + "\n"
		if (System.getProperty("updateApiSpec") == "true") Files.writeString(committed, pretty)
		assertEquals(
			Files.readString(committed), pretty,
			"web/openapi.json is out of date. Run: ./gradlew test --tests '*OpenApiSpecTests' -PupdateApiSpec, then npm run gen:api in web/",
		)
	}
}
