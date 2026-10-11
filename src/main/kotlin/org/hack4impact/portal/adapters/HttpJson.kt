package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Tool
import org.slf4j.LoggerFactory
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

/**
 * How long a call waits out a tool's rate limit before giving up with [RateLimited]: at most [maxWaits] waits,
 * none longer than [maxWait], [maxTotal] in all. Each wait is what the tool asked for (Retry-After) plus a second.
 */
data class RateLimitPolicy(val maxWaits: Int, val maxWait: Duration, val maxTotal: Duration) {
	companion object {
		/** Fail at once (tests, and anything interactive). */
		val NONE = RateLimitPolicy(0, Duration.ZERO, Duration.ZERO)

		/** Scans and syncs: Slack's list methods allow about 20 calls a minute, so a large workspace has to wait. */
		val PATIENT = RateLimitPolicy(maxWaits = 20, maxWait = Duration.ofMinutes(2), maxTotal = Duration.ofMinutes(15))
	}
}

/** A JSON over HTTP call that maps failures to [AdapterException]s the same way for every tool. */
class HttpJson(
	private val tool: Tool,
	private val clock: Clock = Clock.systemUTC(),
	private val client: HttpClient = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(10))
		.build(),
	private val rateLimits: RateLimitPolicy = RateLimitPolicy.NONE,
	private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
	private val log = LoggerFactory.getLogger(javaClass)

	data class Response(val status: Int, val headers: HttpHeaders, val body: JsonNode)

	fun get(url: String, headers: Map<String, String> = emptyMap()): Response =
		send(request(url, headers).GET().build())

	fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): Response =
		send(
			request(url, headers + ("Content-Type" to "application/x-www-form-urlencoded"))
				.POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }))
				.build(),
		)

	/** POSTs [body] as JSON. */
	fun postJson(url: String, body: Any, headers: Map<String, String> = emptyMap()): Response =
		send(
			request(url, headers + ("Content-Type" to "application/json; charset=utf-8"))
				.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
				.build(),
		)

	/** PUTs [body] as JSON. */
	fun putJson(url: String, body: Any, headers: Map<String, String> = emptyMap()): Response =
		send(
			request(url, headers + ("Content-Type" to "application/json; charset=utf-8"))
				.PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
				.build(),
		)

	/** PATCHes [body] as JSON. */
	fun patchJson(url: String, body: Any, headers: Map<String, String> = emptyMap()): Response =
		send(
			request(url, headers + ("Content-Type" to "application/json; charset=utf-8"))
				.method("PATCH", HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
				.build(),
		)

	fun delete(url: String, headers: Map<String, String> = emptyMap()): Response =
		send(request(url, headers).DELETE().build())

	fun post(url: String, headers: Map<String, String> = emptyMap()): Response =
		send(request(url, headers).POST(HttpRequest.BodyPublishers.noBody()).build())

	private fun request(url: String, headers: Map<String, String>) =
		HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json")
			.apply { headers.forEach { (k, v) -> header(k, v) } }

	/** Sends [request], waiting out rate limits as [rateLimits] allows. Only the rate-limited page is repeated. */
	private fun send(request: HttpRequest): Response {
		var waits = 0
		var waited = Duration.ZERO
		while (true) {
			try {
				return attempt(request)
			} catch (e: RateLimited) {
				val wait = e.retryAfter.plusSeconds(1)
				if (waits >= rateLimits.maxWaits || wait > rateLimits.maxWait || waited + wait > rateLimits.maxTotal) throw e
				log.info("{} rate limited on {}; waiting {}s ({} of {})", tool, request.uri().path, wait.seconds, waits + 1, rateLimits.maxWaits)
				sleep(wait)
				waits++
				waited += wait
			}
		}
	}

	private fun attempt(request: HttpRequest): Response {
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
			status >= 400 -> throw Rejected(tool, "$path: HTTP $status${errorMessage(body)?.let { " ($it)" } ?: ""}", status)
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
