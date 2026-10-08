# H4I Member Portal

Member portal and access-provisioning service for Hack4Impact: one registration process gates Slack, GitHub, Notion, Google Workspace, Vaultwarden and Documenso, and a sync engine keeps each tool matching the member database.

Kotlin + Spring Boot 4 on Java 25, built with Gradle. Project knowledge (PRD, build plan, design guide, decisions, runbooks) lives in the wiki at `../Docs/wiki/index.md`.

## Run locally

```sh
./gradlew bootRun                 # http://localhost:8080/api/status
./gradlew build                   # compile + tests
docker build -t h4i-portal:local . && docker run --rm -p 8080:8080 h4i-portal:local
```

Gradle downloads JDK 25 automatically if it isn't installed.

## Deploy

Every push to `main` runs CI, pushes `ghcr.io/<owner>/<repo>:<sha>`, and deploys it to the staging droplet (`deploy/staging/`). Required repo secrets are listed in the wiki's Step 1 runbook.
