package org.hack4impact.portal

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Errors the services raise without an HTTP status, answered in words instead of a bare 500 (code review L1): a state
 * check that failed (approving twice) or a race on unique data (two projects with one name) is a conflict, a bad
 * argument a bad request.
 */
@RestControllerAdvice
class ApiErrors {
	private val log = LoggerFactory.getLogger(javaClass)

	@ExceptionHandler(IllegalStateException::class)
	fun conflict(e: IllegalStateException): ProblemDetail {
		log.info("Conflict: {}", e.message)
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message ?: "That can't be done now")
	}

	@ExceptionHandler(IllegalArgumentException::class)
	fun badRequest(e: IllegalArgumentException): ProblemDetail {
		log.info("Bad request: {}", e.message)
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "That request isn't right")
	}

	@ExceptionHandler(DataIntegrityViolationException::class)
	fun duplicate(e: DataIntegrityViolationException): ProblemDetail {
		log.warn("Data conflict: {}", e.mostSpecificCause.message)
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Someone else just changed this, or it already exists. Reload and try again.")
	}
}
