package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Tool
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A JSON over HTTP call that maps failures to [AdapterException]s the same way for every tool. */
class HttpJson(
	private val tool: Tool,
	private val clock: Clock = Clock.systemUTC(),
	private val client: HttpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build(),
) {
	data class Response(val status: Int, val headers: HttpHeaders, val body: JsonNode)

	fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
		send(request(url, headers).GET().build())

	fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): Response =
		send(
			request(url, headers + ("Content-Type" to "application/x-www-form-urlencoded"))
				.POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }))
				.build(),
		)

	fun post(url: String, headers: Map<String, String> = emptyMap()): Response =
		send(request(url, headers).POST(HttpRequest.BodyPublishers.noBody()).build())

	private fun request(url: String, headers: Map<String, String>) =
		HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
			.apply { headers.forEach { (k, v) -> header(k, v) } }

	private fun send(request: HttpRequest): Response {
		val response = try {
			client.send(request, HttpResponse.BodyHandlers.ofString())
		} catch (e: IOException) {
			throw Unavailable(tool, "${request.uri().path}: ${e.message ?: e.javaClass.simpleName}", e)
		}
		val body = response.body().takeIf { it.isNotBlank() }?.let {
			try { MAPPER.readTree(it) } catch (e: RuntimeException) { null }
		} ?: MAPPER.nullNode()
		val status = response.statusCode()
		val path = request.uri().path
		when {
			status == 429 || (status == 403 && isRateLimit(response, body)) -> throw RateLimited(tool, retryAfter(response.headers()))
			status == 401 || status == 403 -> throw AuthFailed(tool, "$path: HTTP $status")
			status == 404 -> throw NotFound(tool, path)
			status >= 500 -> throw Unavailable(tool, "$path: HTTP $status")
			status >= 400 -> throw Rejected(tool, "$path: HTTP $status${errorMessage(body)?.let { " ($it)" } ?: ""}")
		}
		return Response(status, response.headers(), body)
	}

	/** The tool's own explanation, when it gives one (Google: error.message; Slack and OAuth: error). */
	private fun errorMessage(body: JsonNode): String? {
		val error = body.path("error")
		return (error.text("message") ?: error.takeIf { it.isString }?.asString() ?: body.text("message"))?.take(200)
	}

	// GitHub signals exhausted rate limits with 403 and x-ratelimit-remaining: 0; Google with a rateLimitExceeded reason.
	private fun isRateLimit(response: HttpResponse<String>, body: JsonNode) =
		response.headers().firstValue("x-ratelimit-remaining").orElse(null) == "0" ||
			response.headers().firstValue("retry-after").isPresent ||
			body.toString().contains("RateLimitExceeded", ignoreCase = true)

	private fun retryAfter(headers: HttpHeaders): Duration {
		headers.firstValue("retry-after").orElse(null)?.toLongOrNull()?.let { return Duration.ofSeconds(it) }
		headers.firstValue("x-ratelimit-reset").orElse(null)?.toLongOrNull()?.let {
			return Duration.between(clock.instant(), Instant.ofEpochSecond(it)).coerceAtLeast(Duration.ZERO)
		}
		return Duration.ofSeconds(60)
	}

	companion object {
		val MAPPER: JsonMapper = JsonMapper.builder().build()

		fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
	}
}

/** A string field, or null when missing or JSON null. */
internal fun JsonNode.text(field: String): String? = path(field).takeUnless { it.isMissingNode || it.isNull }?.asString()
