# syntax=docker/dockerfile:1

# --- Build: compile and package the Spring Boot jar ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
 && java -Djarmode=tools -jar build/libs/portal-*-SNAPSHOT.jar extract --layers --launcher --destination extracted

# --- Run: small JRE image, dependencies layered separately from app code ---
FROM eclipse-temurin:25-jre
RUN groupadd --system portal && useradd --system --gid portal portal
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER portal
EXPOSE 8080
# The droplet is shared with Vaultwarden and Documenso, so the heap is capped (PRD: Hosting).
ENV JAVA_TOOL_OPTIONS="-Xmx256m -XX:+ExitOnOutOfMemoryError"
ARG PORTAL_VERSION=dev
ENV PORTAL_VERSION=${PORTAL_VERSION}
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
