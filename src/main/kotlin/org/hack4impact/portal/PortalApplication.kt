package org.hack4impact.portal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PortalApplication

fun main(args: Array<String>) {
	runApplication<PortalApplication>(*args)
}
