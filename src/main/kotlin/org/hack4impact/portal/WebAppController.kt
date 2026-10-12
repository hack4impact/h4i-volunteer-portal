package org.hack4impact.portal

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/** Serves the single-page app for its own routes, so reloading or sharing /chapters/umd/members works. */
@Controller
class WebAppController {
	@GetMapping(
		"/", "/chapters/{code}", "/chapters/{code}/{tab}", "/chapters/{code}/projects/{slug}", "/national",
		"/join/{code}", "/claim/{token}", "/apply",
	)
	fun app() = "forward:/index.html"
}
