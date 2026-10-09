package org.hack4impact.portal.adapters

import org.hack4impact.portal.resolver.Tool

/**
 * A tool held in memory, for local development and tests: same contract as the real adapters, including
 * failures. Set [failure] to make every call throw it.
 */
class InMemoryReadAdapter(
	override val tool: Tool,
	var accounts: List<ToolAccount> = emptyList(),
	var resources: Map<ToolResource, List<ResourceMember>> = emptyMap(),
	var failure: AdapterException? = null,
) : ReadAdapter {
	override fun accounts(): List<ToolAccount> = guarded { accounts }

	override fun resources(): List<ToolResource> = guarded { resources.keys.toList() }

	override fun members(resourceId: String): List<ResourceMember> = guarded {
		resources.entries.firstOrNull { it.key.externalId == resourceId }?.value ?: throw NotFound(tool, resourceId)
	}

	private fun <T> guarded(read: () -> T): T {
		failure?.let { throw it }
		return read()
	}
}
