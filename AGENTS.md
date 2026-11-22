# BuggyWuggy

## Build and verify

- Run `./mvnw` from the root with Java 25. `server` depends on the `web` Maven module, so use `-pl server -am` when targeting it. Maven installs Node 26 under `web/node`; Vite writes `web/target/classes/static`, which ships inside `server/target/buggywuggy.jar`.
- For a local full-stack run: `docker compose up -d` starts only PostgreSQL; build with `./mvnw -DskipTests package`, then run `java -jar server/target/buggywuggy.jar`. Flyway migrates on startup; the first admin setup token is logged. Local config and data default to `config/` and `data/` relative to the process working directory.
- From `web/` with Node 26: `npm ci`, then `npm run dev` (port 5173, proxies `/api` to `BUGGYWUGGY_API_URL` or localhost:8080). Focus checks with `npm run typecheck` or `npm test -- src/lib/history.test.ts`; Vitest only discovers `src/**/*.test.ts` and runs in Node.
- Focus a Java test with `./mvnw -pl server -am -Dskip.npm -Dtest=ConfigTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test`. `-Dskip.npm` skips the UI build and tests, so omit it for tests that need fresh bundled assets (`WebAppIntegrationTest`).
- Forgejo runs `BUGGYWUGGY_TEST_OIDC=mock ./mvnw -B -ntp verify`. Integration tests use real PostgreSQL 16 via Testcontainers; HTTP tests reuse `IntegrationTest` and `ApiClient`. Without Docker, set `BUGGYWUGGY_TEST_TESTCONTAINERS=false` and `BUGGYWUGGY_DB_URL=jdbc:postgresql://localhost:5432/buggywuggy_test` (plus credentials if needed); use a disposable database because tests delete rows. Keep `BUGGYWUGGY_TEST_OIDC=mock` for SSO tests without Docker; the default is Keycloak in a container.

## Changes that span files

- Backend entrypoint: `org.buggywuggy.BuggyWuggyApplication`; frontend providers and routing start in `web/src/main.tsx` and `web/src/App.tsx`. Use `web/src/api/client.ts` for API calls (it handles session cookies and CSRF); `web/src/api/types.ts` aliases generated schema types.
- After changing the API contract, run the updated backend and run `npm run api:types` from `web/` (`BUGGYWUGGY_API_URL` defaults to localhost:8080). Commit the regenerated `web/src/api/schema.d.ts`, not hand edits: CI regenerates it from `/api/openapi` and checks the diff. `OpenApiConfiguration` makes response fields required; use `@ApiNullable` for fields present with a null value. Request schemas ending in `Request` and `IssueQuery` are exempt from the required-field rule.
- Add schema changes as new Flyway migrations in `server/src/main/resources/db/migration`; Hibernate validates rather than creating the schema. `deploy/buggywuggy-deploy` can roll back the jar, not applied migrations.
- For new administrator settings, update `server/src/main/resources/config-template.yml` alongside the built-in defaults; `ConfigTemplateTest` checks coverage. The user and install guides in `docs/` also ship in the in-app Help menu; `HelpDocs.Guide` controls which files are shown.
- Keep UI copy in `web/src/locales/en.json` (i18next); server messages use `server/src/main/resources/i18n/messages*.properties`.
- If `.githooks` is enabled (`git config core.hooksPath .githooks`), ordinary commits bump and stage all three POMs plus `web/package*.json`; unstaged edits in those files block the auto-bump. Use `scripts/set-version.sh <x.y.z>` for an intentional version change. New migrations trigger a minor-version reminder, not a requirement.
