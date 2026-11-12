# BuggyWuggy

BuggyWuggy is an open source, self-hosted issue tracker — a familiar home for teams
coming from a self-hosted Jira installation.

> **Status:** early development. Projects, issues, comments, attachments, links, watchers,
> search, saved filters, administration screens, e-mail notifications and importing from Forgejo work; much of
> Jira's feature set is still to come.

- Installation guide: [docs/installation.md](docs/installation.md)
- User guide: [docs/user-guide.md](docs/user-guide.md)
- Scope and decisions: [docs/scope.md](docs/scope.md)
- Domain model: [docs/domain-model.md](docs/domain-model.md)

## Requirements

- Java 25
- PostgreSQL 16 or newer
- Docker (optional) — for the development database and for running tests with Testcontainers

Maven and Node.js do not need to be installed: use the included wrapper (`./mvnw`); the
Maven build downloads its own Node.js for the web UI. For working on the UI itself, a local
Node.js 26 (e.g. via nvm) is convenient.

## Development setup

### 1. Database

**With Docker:**

```sh
docker compose up -d
```

**With a locally installed PostgreSQL:**

```sh
sudo -u postgres psql -c "create role buggywuggy login password 'buggywuggy'"
sudo -u postgres psql -c "create database buggywuggy owner buggywuggy"
sudo -u postgres psql -c "create database buggywuggy_test owner buggywuggy"
```

### 2. Run

```sh
./mvnw -pl server -am spring-boot:run      # -am also builds the web UI
```

The application, including the web UI, starts on <http://localhost:8080>; health check at
<http://localhost:8080/actuator/health>. The database schema is created automatically
by Flyway on startup.

On the first start without a configuration file, a template with all settings commented out
is written to `config/application.yml` in the working directory (`server/` with
`spring-boot:run`; ignored by git). Print it with `java -jar server/target/buggywuggy.jar
config-template`. See [Configuration file](docs/installation.md#configuration-file).

API documentation (OpenAPI / Swagger UI): <http://localhost:8080/api/docs>

### First-time setup

On a fresh installation the server log shows a **setup token**. Open
<http://localhost:8080>: the web UI asks for the token and creates the first system
administrator. Without a browser, the same works with `curl`:

```sh
# 1. Fetch a CSRF token (stored in cookies.txt)
curl -s -c cookies.txt http://localhost:8080/api/v1/auth/csrf
XSRF=$(awk '$6 == "XSRF-TOKEN" {print $7}' cookies.txt)

# 2. Create the administrator
curl -s -b cookies.txt -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -d '{"setupToken": "<token from log>", "username": "admin", "displayName": "Administrator",
       "email": "admin@example.org", "password": "<at least 12 characters>"}' \
  http://localhost:8080/api/v1/setup/admin
```

For automated installations, set `BUGGYWUGGY_SETUP_TOKEN` to choose the token yourself.

Configuration via environment variables:

| Variable | Default |
|---|---|
| `BUGGYWUGGY_DB_URL` | `jdbc:postgresql://localhost:5432/buggywuggy` |
| `BUGGYWUGGY_DB_USER` | `buggywuggy` |
| `BUGGYWUGGY_DB_PASSWORD` | `buggywuggy` |
| `BUGGYWUGGY_PORT` | `8080` |
| `BUGGYWUGGY_SESSION_TIMEOUT` | `8h` |
| `BUGGYWUGGY_SECURE_COOKIES` | `false` — set to `true` when served over HTTPS |
| `BUGGYWUGGY_SETUP_TOKEN` | *(generated and logged)* |

### 3. Test

With Docker, tests start their own PostgreSQL 16 container:

```sh
./mvnw test
```

Without Docker, point the tests at a dedicated, disposable test database:

```sh
BUGGYWUGGY_TEST_TESTCONTAINERS=false \
BUGGYWUGGY_DB_URL=jdbc:postgresql://localhost:5432/buggywuggy_test \
./mvnw test
```

### Working on the web UI

The UI (`web/`) is React + TypeScript, built with Vite. With the server running on port 8080:

```sh
cd web
npm ci
npm run dev          # http://localhost:5173, with API calls forwarded to :8080
npm test             # unit tests
npm run api:types    # regenerate src/api/schema.d.ts from the running server's OpenAPI document
```

Set `BUGGYWUGGY_API_URL` to use a server on another address. To build only the server,
skipping the UI build: `./mvnw -Dskip.npm ...`.

## Building

```sh
./mvnw package
java -jar server/target/buggywuggy.jar
```

## License

BuggyWuggy is free software, licensed under the
[GNU Affero General Public License v3.0 or later](LICENSE).
