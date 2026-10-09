plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "org.hack4impact"
version = "0.0.1-SNAPSHOT"
description = "H4I member portal and access provisioning"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

// Generates jOOQ classes from the Flyway migrations: starts Postgres in Testcontainers, migrates, reads the schema.
val codegen = sourceSets.create("codegen")

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jooq")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jooq-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	"codegenImplementation"("org.jooq:jooq-codegen")
	"codegenImplementation"("org.flywaydb:flyway-core")
	"codegenImplementation"("org.flywaydb:flyway-database-postgresql")
	"codegenImplementation"("org.testcontainers:testcontainers-postgresql")
	"codegenRuntimeOnly"("org.postgresql:postgresql")
	"codegenRuntimeOnly"("org.slf4j:slf4j-nop")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

val migrations = layout.projectDirectory.dir("src/main/resources/db/migration")
val jooqOutput = layout.buildDirectory.dir("generated/jooq")

val generateJooq = tasks.register<JavaExec>("generateJooq") {
	group = "build"
	description = "Generates jOOQ classes from the Flyway migrations (needs Docker)."
	classpath = codegen.runtimeClasspath
	mainClass = "org.hack4impact.portal.codegen.JooqCodegenKt"
	inputs.dir(migrations)
	outputs.dir(jooqOutput)
	args(migrations.asFile.absolutePath, jooqOutput.get().asFile.absolutePath)
}

kotlin.sourceSets.named("main") {
	kotlin.srcDir(jooqOutput)
}

tasks.named("compileKotlin") {
	dependsOn(generateJooq)
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Only the executable Spring Boot jar is needed; the Dockerfile packages it.
tasks.named<Jar>("jar") {
	enabled = false
}
