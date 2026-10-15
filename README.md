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

## The member database

Since 2026-10-14 the national member database is an integration (wiki: staged-rollout, decisions 159 and 162), the same as in the stage 1 app (`../Membership-Portal-Stage-1`):

- **Pull** (`PORTAL_MEMBERDB_PULL=true`, every 15 minutes, skipped when no table changed): the importer below, run on a schedule. A record its person confirmed is never overwritten.
- **Write**: approving, claiming or finishing a roster invite queues the person (`member_db_write`); a worker writes their volunteer row, school profile and GitHub and Google accounts, one member-database transaction each, with retries. It never deletes. Writes start in dry run (national sees what would be written on the National page); leaving it needs `PORTAL_MEMBERDB_WRITE=true`.
- **Drift**: a field the portal owns that someone changes in the member database is reported on the National page, not taken. So is a volunteer the member database soft-deletes ("Record deleted there"): the portal keeps them until national decides.
- **Join and Claim ask** major, degree type and graduation season and year (the member database requires them on a school profile). **National seeds `academic_terms`** before Join opens.
- **Changes for Khoa**: `scripts/member-db-changes.sql`.
- Settings: `MEMBER_DB_URL`, `MEMBER_DB_USERNAME`, `MEMBER_DB_PASSWORD`, `PORTAL_MEMBERDB_PULL`, `PORTAL_MEMBERDB_WRITE`.

## Importing the national member database

The importer copies it in, never overwrites anything edited or claimed in the portal, and writes a report of everything that needs a person to look at. It runs once from the command line (below), or on a schedule as the pull above.

```sh
# Against the fake member DB that compose.yaml starts (no real people):
./gradlew bootRun --args='--spring.profiles.active=import'

# Against a real (preferably anonymized) copy:
MEMBER_DB_URL=jdbc:postgresql://<host>:5432/<db> MEMBER_DB_USERNAME=portal_import MEMBER_DB_PASSWORD=... \
  ./gradlew bootRun --args='--spring.profiles.active=import'
```

If the member DB isn't reachable from your machine (it usually isn't), run `scripts/member-db-export.sql` in pgAdmin's Query Tool, save the result as CSV outside the repo, and load it with `scripts/load-member-db-export.sh <file.csv>`; then import with `MEMBER_DB_URL=jdbc:postgresql://localhost:5434/memberdb` (user and password `memberdb`).

The report is written to `build/import-report.md`. It contains personal data, so don't commit or share it outside national. `scripts/anonymize-member-db.sql` anonymizes a copy of the member DB for rehearsals.

## Preflight

Before going live (and after changing settings), check the deployment with its real settings. It only reads: nothing is created, written or emailed. The tool adapters have their own check (`adapter-check` profile).

```sh
./gradlew bootRun --args='--spring.profiles.active=preflight'                               # every chapter taking applications
./gradlew bootRun --args='--spring.profiles.active=preflight --portal.preflight.chapters=umd'  # just the pilot
```

It prints one line per check (OK, WARN or FAIL) and exits 1 if anything failed:

- **Portal:** the public address, Google and GitHub sign-in set, email not in `log` mode, at least one national admin.
- **Member database:** the connection, the change check, `SELECT` on the six tables it reads, `INSERT` and `UPDATE` on the three it writes, no `DELETE`, Khoa's changes (`scripts/member-db-changes.sql`: keys, unique accounts, `updated_at` triggers), and the switches.
- **Google:** account creation's service account can look up the admin; a sandbox org-unit scope is flagged.
- **Documenso:** each active template is a template with a signer; the webhook secret.
- **Each chapter:** school email domains, Google domain and org unit, a current lead or co-lead, its questions, and academic terms after this year.

## Deploy

Every push to `main` runs CI and pushes `ghcr.io/hack4impact/h4i-volunteer-portal:<sha>` and `:main` (public). The Dockerfile packages the jar built by `./gradlew bootJar`.

Staging runs on a developer's computer: `deploy/staging/up.sh [tag]` pulls an image and starts it with its own Postgres on http://localhost:8081. It needs `deploy/staging/.env` with `PORTAL_DB_PASSWORD`, built from the vault with `deploy/fetch-secrets.sh "portal staging" deploy/staging/.env`. Details are in the wiki's step 1 runbook.
