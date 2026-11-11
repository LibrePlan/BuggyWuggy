# BuggyWuggy — Domain Model (v1)

Status: draft · Last updated: 2026-11-11 · See also: [scope.md](scope.md)

This document describes the entities, their relations and the database conventions for
v1. It is the basis for the first Flyway migrations. Entities marked *(later)* are not
built in v1, but the v1 model is designed so they fit in without restructuring.

## 1. Conventions

| Topic | Convention |
|---|---|
| Naming | `snake_case`, singular table names (`issue`, `comment`). `user` is reserved in PostgreSQL, so the table is `user_account`. |
| Primary keys | `bigint generated always as identity`. Compact, fast, ordered; public URLs use issue keys (`BUG-123`) rather than IDs anyway. |
| Timestamps | `timestamptz`, stored in UTC. Never filled implicitly by the database when an importer supplies a value (see §8). |
| Text | `text` columns; length limits enforced in the application, not with `varchar(n)`. |
| Rich text | Markdown (CommonMark + GFM tables/task lists). Imported Jira wiki markup is converted at import time. |
| Enumerations | Small fixed sets (e.g. status category) are `text` with a `check` constraint, mapped to Java enums. Admin-editable lists (priorities, issue types) are tables. |
| Concurrency | Optimistic locking with a `version bigint` column on entities edited concurrently (`issue`, `comment`, `project`). |
| Deletion | Hard deletes in v1, with `on delete` rules defined per foreign key. Change history keeps a textual record of what was removed. |
| Sort order | Admin-orderable lists have a `position int` column. |

## 2. Overview

```mermaid
erDiagram
    PROJECT ||--o{ ISSUE : contains
    PROJECT ||--o{ COMPONENT : has
    PROJECT ||--o{ PROJECT_VERSION : has
    PROJECT }o--|| PERMISSION_SCHEME : uses
    PROJECT }o--|| WORKFLOW_SCHEME : uses
    WORKFLOW_SCHEME ||--o{ WORKFLOW_SCHEME_ENTRY : maps
    WORKFLOW_SCHEME_ENTRY }o--|| WORKFLOW : to
    WORKFLOW ||--o{ WORKFLOW_TRANSITION : defines
    ISSUE }o--|| ISSUE_TYPE : "is of"
    ISSUE }o--|| STATUS : "is in"
    ISSUE }o--o| PRIORITY : has
    ISSUE }o--o| RESOLUTION : has
    ISSUE }o--o| ISSUE : "parent"
    ISSUE ||--o{ COMMENT : has
    ISSUE ||--o{ ATTACHMENT : has
    ISSUE ||--o{ CHANGE_GROUP : history
    CHANGE_GROUP ||--|{ CHANGE_ITEM : contains
    ISSUE ||--o{ ISSUE_LINK : "source/target"
    ISSUE ||--o{ WATCHER : watched
    USER_ACCOUNT ||--o{ GROUP_MEMBERSHIP : in
    USER_GROUP ||--o{ GROUP_MEMBERSHIP : has
    PROJECT ||--o{ PROJECT_ROLE_ACTOR : "role members"
```

## 3. Users, groups and authentication

### `user_account`
| Column | Type | Notes |
|---|---|---|
| id | bigint PK | |
| username | text, unique (case-insensitive) | Login name; immutable after creation in v1 |
| display_name | text | |
| email | text, unique (case-insensitive), nullable | Nullable for placeholder users |
| password_hash | text, nullable | Argon2id, stored with an `{id}` prefix (e.g. `{argon2}…`), so hashes in other formats — such as Jira's `{PKCS5S2}…` after an import — can be verified and upgraded on login. Null for placeholder users and (later) SSO-only users |
| active | boolean | Inactive users cannot log in but remain visible in history |
| placeholder | boolean | Created by an importer for authors without an account (§8) |
| system_admin | boolean | Global administrator |
| locale, time_zone | text, nullable | Per-user preferences; `null` = installation default |
| created_at, last_login_at | timestamptz | |

Case-insensitive uniqueness via a unique index on `lower(username)` / `lower(email)`.

### `user_group`, `group_membership`
- `user_group(id, name unique, description)`
- `group_membership(group_id, user_id)` — composite PK.
- Default groups created at installation: `administrators`, `users`.

### Authentication support tables
- `password_reset_token(id, user_id, token_hash, expires_at, used_at)`
- Sessions: Spring Session JDBC tables `spring_session` and `spring_session_attributes` (Flyway V10). Sessions survive restarts and can be ended per user (deactivation, password reset, change of global permissions).
- *(later)* `external_identity(user_id, provider, subject)` for LDAP / OIDC / SAML.

## 4. Projects

### `project`
| Column | Type | Notes |
|---|---|---|
| id | bigint PK | |
| key | text, unique | Uppercase, `^[A-Z][A-Z0-9_]{1,9}$` |
| name | text, unique | |
| description | text (Markdown) | |
| lead_id | FK user_account | |
| default_assignee | text | `UNASSIGNED` or `PROJECT_LEAD` |
| issue_counter | bigint | Last used issue number; see §5.2 |
| permission_scheme_id | FK permission_scheme | |
| workflow_scheme_id | FK workflow_scheme | |
| archived | boolean | Read-only when archived |
| created_at, version | | |

### `project_key_history`
`(old_key text unique, project_id)` — when a project key changes, old issue keys keep
resolving (`OLD-12` redirects to `NEW-12`).

### `component`
`(id, project_id, name, description, lead_id nullable, default_assignee)` — `default_assignee` ∈ `PROJECT_DEFAULT`, `UNASSIGNED`, `PROJECT_LEAD`, `COMPONENT_LEAD`; unique `(project_id, lower(name))`.

### `project_version`
`(id, project_id, name, description, start_date, release_date, released, archived, position)` — unique `(project_id, lower(name))`.
Named `project_version` to avoid confusion with the optimistic-locking `version` columns.

## 5. Issues

### 5.1 `issue`
| Column | Type | Notes |
|---|---|---|
| id | bigint PK | |
| project_id | FK project | |
| number | bigint | Unique per project: unique `(project_id, number)` |
| issue_type_id | FK issue_type | |
| status_id | FK status | Must be a status of the issue's workflow |
| priority_id | FK priority, nullable | |
| resolution_id | FK resolution, nullable | Null = unresolved |
| parent_id | FK issue, nullable | Sub-task → parent issue; issue → epic (see rules) |
| summary | text, not null | |
| description | text (Markdown), nullable | |
| reporter_id | FK user_account, nullable | |
| assignee_id | FK user_account, nullable | |
| creator_id | FK user_account, nullable | Who actually created it (differs from reporter on import or "create on behalf of") |
| due_date | date, nullable | |
| created_at, updated_at, resolved_at | timestamptz | `resolved_at` set when resolution becomes non-null |
| version | bigint | Optimistic locking |
| search_vector | tsvector, generated | See §9 |

**Parent rules** (enforced in the domain layer):
- A sub-task must have a parent, and that parent must not be a sub-task or epic.
- A standard issue may have an epic as parent.
- An epic has no parent in v1.
- Parent and child are always in the same project (v1).
- Changing the issue type to another hierarchy level is allowed only when the issue has no
  children, and the parent must be valid for the new type in the same request (e.g. task →
  sub-task together with a parent; sub-task → task together with clearing the parent).
- Deleting an issue deletes its sub-tasks; deleting an epic keeps its issues without parent.

The **issue key** (`BUG-123`) is not stored; it is `project.key || '-' || issue.number`.

### 5.2 Issue numbering
New numbers come from `update project set issue_counter = issue_counter + 1 where id = ? returning issue_counter`
inside the creating transaction. The row lock makes this safe under concurrency, and no
numbers are reused. An importer may set `number` explicitly and raises `issue_counter`
to the maximum afterwards.

### 5.3 `issue_key_history`
`(project_id, number, issue_id)` with unique `(project_id, number)` — when an issue is moved to
another project it gets a new number; its old key keeps resolving.

### 5.4 Multi-valued system fields
- `issue_label(issue_id, label)` — PK `(issue_id, label)`; labels are free text without spaces.
- `issue_component(issue_id, component_id)`
- `issue_version(issue_id, version_id, kind)` — `kind` ∈ `AFFECTS`, `FIX`.

### 5.5 Lookup tables (admin-editable, global)
| Table | Columns | v1 seed data |
|---|---|---|
| `issue_type` | id, name, description, icon, hierarchy_level, position | Epic (1), Story / Task / Bug (0), Sub-task (−1) |
| `priority` | id, name, description, icon, color, position, is_default | Highest, High, Medium (default), Low, Lowest |
| `resolution` | id, name, description, position, is_default | Fixed, Won't Fix, Duplicate, Cannot Reproduce, Done |
| `status` | id, name, description, category, position | Open, In Progress, Resolved, Closed, Reopened |

`issue_type.hierarchy_level` drives the parent rules (epic = 1, standard = 0, sub-task = −1),
which also prepares for deeper hierarchies later.

`status.category` ∈ `TODO`, `IN_PROGRESS`, `DONE` — used for colouring now and for boards later.

## 6. Collaboration

### `comment`
`(id, issue_id, author_id, body (Markdown), created_at, updated_at, update_author_id, version, search_vector)`

*(later)* `visibility_role_id` / `visibility_group_id` for restricted comments; the
columns can be added without changing existing rows.

### `attachment`
`(id, issue_id, author_id, file_name, media_type, size_bytes, sha256, storage_key, created_at)`

File contents are stored outside the database through a `BlobStore` interface. v1
implementation: local filesystem under the data directory, path derived from
`storage_key`. *(later)* S3-compatible storage.

### `issue_link_type` and `issue_link`
- `issue_link_type(id, name, outward_description, inward_description)` — seeded: Blocks
  (blocks / is blocked by), Relates (relates to / relates to), Duplicate (duplicates / is
  duplicated by), Cloners (clones / is cloned by).
- `issue_link(id, link_type_id, source_issue_id, target_issue_id, created_at)` — unique
  `(link_type_id, source_issue_id, target_issue_id)`.

### `watcher`
`(issue_id, user_id, created_at)` — PK `(issue_id, user_id)`. Reporter and creator are added automatically.

## 7. Workflow and permissions (data-driven, fixed content in v1)

### 7.1 Workflow
- `workflow(id, name, description)`
- `workflow_status(workflow_id, status_id, initial boolean)` — exactly one initial status per workflow.
- `workflow_transition(id, workflow_id, name, from_status_id nullable, to_status_id, position)` —
  `from_status_id = null` means "available from any status" (global transition).
- *(later)* transition conditions, validators, post-functions and screens hang off `workflow_transition`.

Resolution handling in v1 is built-in: moving to a status with category `DONE` requires
a resolution; moving out of it clears the resolution. When no resolution is given, the
current one is kept (e.g. Resolved → Closed), otherwise the default resolution is used.
`resolved_at` is set when an issue enters a done status and kept while it moves between
done statuses. Transitions to the current status are never offered.

- `workflow_scheme(id, name, description, is_default)` — exactly one default, used for new projects.
- `workflow_scheme_entry(workflow_scheme_id, issue_type_id nullable, workflow_id)` —
  `issue_type_id = null` is the default for the scheme.

v1 seeds one workflow ("Default"), one workflow scheme, and assigns it to every project.

### 7.2 Roles and permissions
- `project_role(id, name, description)` — seeded: Administrators, Developers, Users.
- `project_role_actor(id, project_id, project_role_id, user_id nullable, group_id nullable)` —
  exactly one of `user_id` / `group_id` is set.
- `permission_scheme(id, name, description, is_default)` — exactly one default, used for new projects.
- `permission_grant(id, permission_scheme_id, permission, holder_type, holder_ref)`
  - `permission` ∈ `BROWSE_PROJECT`, `CREATE_ISSUE`, `EDIT_ISSUE`, `DELETE_ISSUE`,
    `ASSIGN_ISSUE`, `ASSIGNABLE_USER`, `TRANSITION_ISSUE`, `ADD_COMMENT`,
    `EDIT_ALL_COMMENTS`, `EDIT_OWN_COMMENTS`, `DELETE_ALL_COMMENTS`, `DELETE_OWN_COMMENTS`,
    `CREATE_ATTACHMENT`, `DELETE_ALL_ATTACHMENTS`, `DELETE_OWN_ATTACHMENTS`, `LINK_ISSUE`,
    `MANAGE_WATCHERS`, `ADMINISTER_PROJECT`
  - `holder_type` ∈ `PROJECT_ROLE`, `GROUP`, `USER`, `REPORTER`, `ASSIGNEE`, `PROJECT_LEAD`, `ANYONE_LOGGED_IN`, `ANONYMOUS`
  - `holder_ref` holds the role/group/user ID where applicable.

Global permissions (system admin, create projects, browse users) live on `user_account.system_admin`
plus `global_permission_grant(permission, group_id)`.

v1 seeds one default permission scheme; the UI only edits role membership per project.

**Evaluation rules**
- Permissions are evaluated per request from the database, so role and group changes take effect immediately.
- `REPORTER` and `ASSIGNEE` grants only apply in the context of a specific issue.
- Users with the global `ADMINISTER` permission get `ADMINISTER_PROJECT` on every project
  (they can configure it), but still need `BROWSE_PROJECT` through the scheme to see issues.
- A project the user can neither browse nor administer is reported as *not found*.
- Changing a project key, archiving and deleting a project require the global `ADMINISTER` permission.
- Archived projects are read-only and hidden from project lists unless requested.

**Default role members** — `project_role_default_actor(project_role_id, group_id)` lists the
groups added to a role when a project is created (seeded: `users` → Users,
`administrators` → Administrators). The project lead is always added to the Administrators role.

## 8. Change history and import support

### 8.1 History
- `change_group(id, issue_id, author_id nullable, created_at)`
- `change_item(id, change_group_id, field_key, field_kind, old_value, old_text, new_value, new_text)`
  - `field_key`: field identifier. System fields: `summary`, `description`, `issuetype`, `priority`,
    `assignee`, `reporter`, `duedate`, `labels`, `component`, `version` (affects version),
    `fixversion`; from the workflow step: `status`, `resolution`. These match Jira's field ids
    where possible, so imported history needs little mapping.
  - Single-valued fields produce one item per change. `labels` produces one item with the full
    old and new label list (space-separated). `component`, `version` and `fixversion` produce one
    item per added or removed value, as Jira does.
  - Updates that change nothing produce no change group and leave `updated_at` untouched.
  - `field_kind` ∈ `SYSTEM`, `CUSTOM` (later).
  - `*_value`: machine value (ID or raw value); `*_text`: human-readable value at the time of change.

This mirrors Jira's `changegroup` / `changeitem` structure, so imported history maps
one-to-one. History rows are append-only: never updated, only deleted together with
their issue.

Comments and attachments are not tracked in `change_item` (they carry their own
timestamps), except for attachment deletions, which are recorded so the history stays complete.

### 8.2 Import principles in the model
1. **Explicit timestamps and authors.** All `created_at` / `updated_at` / `resolved_at` and
   author columns are set by the application, never by database defaults, so an importer
   can supply original values.
2. **Placeholder users.** Unknown authors become `user_account` rows with
   `placeholder = true, active = false`. An admin can later merge a placeholder into a real account.
3. **External references.**
   `external_ref(id, import_source_id, source_type, source_id, local_type, local_id, imported_at)` —
   unique `(import_source_id, source_type, source_id)`. Makes imports repeatable and
   incremental, and allows links, parents and history to be resolved after all issues exist.
4. *(later)* `import_source(id, kind, base_url, label)` and `import_job(id, import_source_id, status, started_at, finished_at, log)`.
   `import_source` is created in v1 already because `external_ref` references it.

## 9. Search (PostgreSQL only)

- `issue.search_vector` — generated column:
  `setweight(to_tsvector('simple', summary), 'A') || setweight(to_tsvector('english', summary), 'A') ||
   setweight(to_tsvector('simple', coalesce(description, '')), 'B') || setweight(to_tsvector('english', coalesce(description, '')), 'B')`
- `comment.search_vector` — generated from `body`, weight `C`, same two configurations.
- GIN index on both `search_vector` columns.
- `pg_trgm` GIN index on `issue.summary` for partial and typo-tolerant matching.
- Text search matches issues whose own vector or any comment's vector matches; ranking via `ts_rank` on the best match.
- Structured filters (project, status, assignee, labels, versions, dates, …) are plain SQL
  with B-tree indexes on the filtered foreign keys and dates.

Indexes are maintained transactionally by PostgreSQL; no re-indexing is ever needed.

## 10. Other tables

- `saved_filter(id, owner_id, name, description, query jsonb, shared_with text, created_at, updated_at)` —
  `query` holds the structured filter definition; `shared_with` ∈ `PRIVATE`, `LOGGED_IN`, `PROJECT` (with project_id).
  *(later)* a text query language stored alongside.
- `outbox_event(id, event_type, payload jsonb, created_at, processed_at, attempts, last_error)` —
  transactional outbox. Domain events (issue created, updated, commented, …) are written in the same
  transaction; a background worker sends e-mails from them. Later also webhooks.
- `user_preference(user_id, key, value)` — e.g. e-mail notification opt-outs.
- `application_setting(key, value jsonb)` — base URL, mail settings, installation defaults.

## 11. Planned later (designed for, not built)

| Feature | How it fits |
|---|---|
| Custom fields | `field_definition(id, key, name, type, …)` + `field_context` (project/issue type scope) + `issue_field_value(issue_id, field_id, text_value, number_value, date_value, option_id, user_id)`. System fields get rows in `field_definition` too, so field-level config (screens, required fields) treats both alike. |
| Configurable workflows | Admin UI on existing `workflow*` tables; add conditions/validators/post-functions. |
| Boards & sprints | `board`, `sprint`, `issue_sprint`, plus a `rank` column (lexorank-style text) on `issue`. Board columns map to statuses. |
| Restricted comments | Visibility columns on `comment`. |
| Webhooks | Consumers of `outbox_event`. |
| SSO / LDAP | `external_identity`; groups synced into `user_group`. |
