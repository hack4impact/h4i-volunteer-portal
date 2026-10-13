package org.hack4impact.portal.tools

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** A tool as the web app shows it: every list, name, box and order on the screens comes from these. */
data class ToolInfo(
	val key: String,
	val name: String,
	/** After a resource's name in previews: "umd-rise (Google group)". */
	val resourceLabel: String,
	/** The project page's box: "Slack channels". */
	val resourcesTitle: String,
	val order: Int,
	val projectOrder: Int,
	/** "#" for Slack channels. */
	val namePrefix: String,
	/** For the add form: "{chapter}-{project}-eng". */
	val nameExample: String,
	/** New resources wait for the project agreement by default (repositories, vaults). */
	val sensitive: Boolean,
	val hasAdminLevel: Boolean,
	/** Chapters attach its resources to projects. */
	val projectResources: Boolean,
)

@RestController
class ToolController(private val tools: ToolRegistry) {
	/** The tools the portal knows, in list order (wiki: tool plugins). Any signed-in person. */
	@GetMapping("/api/tools")
	fun tools(): List<ToolInfo> = tools.all.map {
		ToolInfo(it.key, it.name, it.resourceLabel, it.resourcesTitle, it.order, it.projectOrder, it.namePrefix, it.nameExample, it.sensitive, it.hasAdminLevel, it.projectResources)
	}
}
