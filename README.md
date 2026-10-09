# H4I Member Portal

Member portal and access-provisioning service for Hack4Impact: one registration process gates Slack, GitHub, Notion, Google Workspace, Vaultwarden and Documenso, and a sync engine keeps each tool matching the portal's database.

Kotlin + Spring Boot 4 on Java 25, PostgreSQL 17 with Flyway and jOOQ, built with Gradle. Project knowledge (PRD, build plan, design guide, decisions, runbooks) lives in the wiki at `../Docs/wiki/index.md`.

## Requirements

- Docker. Tests, jOOQ code generation and local runs all start Postgres in containers.
- Nothing else: Gradle downloads JDK 25 if it isn't installed.

## Run locally

```sh
./gradlew build                   # generate jOOQ classes, compile, run tests
./gradlew bootRun                 # starts compose.yaml (Postgres), then the app on http://localhost:8080/api/status
```

## Database

The portal's own database is the source of truth. Migrations are in `src/main/resources/db/migration` (schema `portal`) and run on startup. jOOQ classes are generated from them into `build/generated/jooq` by `./gradlew generateJooq`, which runs automatically before compiling.

## Importing the national member database

The member DB is read-only to the portal and only used for seeding. The importer copies it in, never overwrites anything edited or claimed in the portal, and writes a report of everything that needs a person to look at.

```sh
# Against the fake member DB that compose.yaml starts (no real people):
./gradlew bootRun --args='--spring.profiles.active=import'

# Against a real (preferably anonymized) copy:
MEMBER_DB_URL=jdbc:postgresql://<host>:5432/<db> MEMBER_DB_USERNAME=portal_import MEMBER_DB_PASSWORD=... \
  ./gradlew bootRun --args='--spring.profiles.active=import'
```

If the member DB isn't reachable from your machine (it usually isn't), run `scripts/member-db-export.sql` in pgAdmin's Query Tool, save the result as CSV outside the repo, and load it with `scripts/load-member-db-export.sh <file.csv>`; then import with `MEMBER_DB_URL=jdbc:postgresql://localhost:5434/memberdb` (user and password `memberdb`).

The report is written to `build/import-report.md`. It contains personal data, so don't commit or share it outside national. `scripts/anonymize-member-db.sql` anonymizes a copy of the member DB for rehearsals.

## Deploy

Every push to `main` runs CI and pushes `ghcr.io/hack4impact/h4i-volunteer-portal:<sha>` and `:main` (public). The Dockerfile packages the jar built by `./gradlew bootJar`.

Staging runs on a developer's computer: `deploy/staging/up.sh [tag]` pulls an image and starts it with its own Postgres on http://localhost:8081. It needs `deploy/staging/.env` with `PORTAL_DB_PASSWORD`, built from the vault with `deploy/fetch-secrets.sh "portal staging" deploy/staging/.env`. Details are in the wiki's step 1 runbook.
