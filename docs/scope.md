# BuggyWuggy — Project Scope (v1)

Status: draft · Last updated: 2026-11-11

## 1. Vision

BuggyWuggy is a fully open source, self-hosted issue tracker that offers a familiar
home for teams who used (or still use) a self-hosted Jira installation. It aims to be
functionally comparable, easy to install on your own server, and easy to migrate to.

- **License:** AGPL-3.0-or-later
- **Target users:** teams and organisations looking for a self-hosted alternative to Jira
  Server / Data Center / Cloud.
- **Non-goal:** copying Jira's name, branding, visual design or code. BuggyWuggy is an
  independent implementation with its own identity.

## 2. Scope of v1 (MVP)

### In scope

| Area | Contents |
|---|---|
| Users & auth | Local users, groups, login, password reset, admin role |
| Projects | Projects with a unique key (e.g. `BUG`), issues numbered per project (`BUG-123`) |
| Issue types | Bug, Task, Story, Epic, Sub-task |
| Fields | Summary, description (Markdown), priority, status, resolution, assignee, reporter, labels, components, affects/fix versions, due date |
| Workflow | One fixed default workflow: Open → In Progress → Resolved → Closed (+ Reopen) |
| Collaboration | Comments, attachments, watchers, issue links (blocks / relates to / duplicates) |
| History | Full change history per issue (who changed what, when) |
| Search | Filter/search page with structured filters, saved filters |
| Permissions | Basic per-project permissions (browse, create, edit, comment, administer) via roles |
| Notifications | E-mail notifications for watchers, assignee and reporter |
| API | REST API (OpenAPI-documented) — the web UI uses the same API |

### Out of scope for v1 (planned later)

- Configurable workflows, workflow schemes, screen schemes
- Custom fields
- Full query language (JQL-like)
- Agile boards (Scrum / Kanban), sprints, backlog
- Dashboards and gadgets
- Webhooks
- Plugin / extension system
- Reports
- LDAP / SAML / OIDC single sign-on
- Clustering / high availability
- **Importer** (Jira backup) and **migrator** (Jira REST) — see §5
- Optional Jira-compatible REST subset, so existing scripts and tools keep working

## 3. Technology choices

| Layer | Choice |
|---|---|
| Language | Java 25 (LTS) |
| Framework | Spring Boot 4 |
| Database | PostgreSQL 16+ (only supported database in v1) |
| Migrations | Flyway |
| Data access | Spring Data JPA / Hibernate, jOOQ where queries get complex |
| Search | PostgreSQL only: built-in full-text search (`tsvector` + GIN index) for text, `pg_trgm` for partial/fuzzy matching, plain SQL for structured filters. No external search engine, so no re-indexing — the index is updated in the same transaction as the data. |
| API | REST + JSON, OpenAPI spec as the contract |
| Frontend | React + TypeScript, built with Vite, served by the Spring Boot application |
| Build | Maven (with Maven Wrapper); frontend built via `frontend-maven-plugin` |
| Packaging | One self-contained executable JAR and an official Docker image; Docker Compose example with PostgreSQL |
| Tests | JUnit 5, Testcontainers (real PostgreSQL), Playwright for end-to-end UI tests |
| i18n | UI ships in English only in v1, but all user-facing text is externalised from day one (backend: Spring `MessageSource`; frontend: i18next). No hard-coded UI strings. |

### Why React + TypeScript for the frontend

- **API-first is required anyway.** Jira users expect a complete REST API, and the
  migrator and integrations need one too. If the web UI uses that same API, it stays
  complete and well tested.
- **Long-lived and mainstream.** React and TypeScript have the largest ecosystem and
  contributor pool, which matters for an open source project.
- **Rich interaction later.** Boards with drag & drop, inline editing and live updates
  are much easier in a SPA.
- **Still simple to deploy.** The built frontend is bundled into the JAR, so users
  install a single application.

## 4. Data model principles

These decisions are made now so later features do not require painful rewrites.

1. **Field abstraction from day one.** System fields are stored in normal columns, but
   the domain model treats every field through a common field definition. Custom fields
   can then be added later as just another field type.
2. **Workflow as data.** Even the single fixed v1 workflow is stored as data (statuses,
   transitions), not hard-coded. Configurable workflows become an admin UI on top.
3. **Stable internal IDs, separate visible keys.** Issues have an internal ID plus a
   project key + number (`BUG-123`). Keys stay valid after project key changes via a
   key-history table.
4. **Import-friendly timestamps and authorship.** `created`, `updated`, `resolved`,
   author and reporter can be set explicitly (by an importer) instead of always being
   generated automatically.
5. **External ID mapping.** A generic table maps imported objects to their source
   (`source_system`, `source_type`, `source_id` → local ID). This makes imports
   repeatable/incremental and lets links between objects be restored.
6. **History is append-only and importable.** Change history uses a generic
   field/old value/new value structure, compatible with Jira's change groups.
7. **Inactive / placeholder users.** Users from an import who do not (yet) have an
   account can exist as inactive users, so authorship is preserved.

## 5. Importer and migrator (planned after v1)

Two complementary ways to bring data over:

| | Backup importer | REST migrator |
|---|---|---|
| Source | Jira Server / Data Center backup ZIP (`entities.xml` + `activeobjects.xml` + attachments directory) | Running Jira instance (Server, Data Center or Cloud) via its REST API |
| Use case | Old or decommissioned installations, full-fidelity migration | Live instances, Cloud, selective migration of projects |
| Fidelity | Highest — includes history, schemes, users | As much as the API exposes (similar to Forgejo's migrations) |
| Runs | Admin action, large background job | Admin or project-level action, background job with progress |

Both share one internal import pipeline: **source reader → neutral intermediate model →
mapping (users, statuses, fields, types) → writer**. The mapping step lets admins map
unknown statuses, issue types and fields to their BuggyWuggy equivalents.

## 6. Decisions

| Topic | Decision |
|---|---|
| Name | BuggyWuggy |
| Build tool | Maven — declarative, very stable across versions, known by virtually every Java developer, which lowers the bar for contributors |
| UI language | English in v1; code fully internationalised so translations can be added without code changes |
| PostgreSQL | 16 or newer |
| Search engine | No external search engine (OpenSearch/Elasticsearch/Lucene); search runs entirely in PostgreSQL to avoid re-indexing and index drift |
