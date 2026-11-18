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
| Rich text | Markdown (CommonMark + GFM tables/task lists). Imported Jira wiki markup, Redmine Textile and Trac WikiFormatting are converted at import time. |
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
| password_hash | text, nullable | Argon2id, stored with an `{id}` prefix (e.g. `{argon2}…`), so hashes in other formats — such as Jira's `{PKCS5S2}…` after an import — can be verified and upgraded on login. Null for placeholder users and SSO-only users |
| active | boolean | Inactive users cannot log in but remain visible in history |
| placeholder | boolean | Created by an importer for authors without an account (§8) |
| system_admin | boolean | Global administrator |
| locale, time_zone | text, nullable | Per-user preferences; `null` = installation default |
| created_at, last_login_at | timestamptz | |

Case-insensitive uniqueness via a unique index on `lower(username)` / `lower(email)`.

### `user_two_factor`, `user_recovery_code`
Two-factor login (TOTP), used when `buggywuggy.security.two-factor.enabled` is on.
- `user_two_factor(user_id pk fk, secret, enrolled_at, last_step)` — the TOTP secret, encrypted
  with AES-256-GCM under `<data dir>/secrets/two-factor.key` (nonce + ciphertext + tag, the
  user id bound as associated data); `last_step` is the last accepted 30-second step, so a code
  is never accepted twice. No row: the user goes through the enrollment wizard at the next
  login. An administrator reset deletes the row.
- `user_recovery_code(id, user_id fk, code_hash, used_at)` — 10 one-time codes per user,
  SHA-256 hashed; replaced at every enrollment, deleted with a reset.

A login that passed the second factor carries the authority `TWO_FACTOR_VERIFIED` in its
session; with two-factor login on, sessions without it are ended.

### `user_identity`
Links to accounts at OpenID Connect providers ([single sign-on](single-sign-on.md)).
- `user_identity(id, user_id fk, provider, subject, email, created_at, last_login_at)` —
  `provider` is the provider's id from the configuration, `subject` the IdP's `sub` claim;
  unique `(provider, subject)` and `(user_id, provider)`: one identity per provider per
  account. `email` is the address the IdP gave at the last login (informational). Deleted
  with the account, or by an administrator (*Unlink*).

A single sign-on login carries the authority `SSO_LOGIN` in its session; with two-factor
login on, such sessions are kept without `TWO_FACTOR_VERIFIED`.

### `user_group`, `group_membership`
- `user_group(id, name unique, description)`
- `group_membership(group_id, user_id)` — composite PK.
- Default groups created at installation: `administrators`, `users`.

### Authentication support tables
- `password_reset_token(id, user_id, token_hash, expires_at, used_at)`
- Sessions: Spring Session JDBC tables `spring_session` and `spring_session_attributes` (Flyway V10). Sessions survive restarts and can be ended per user (deactivation, password reset, change of global permissions).

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
| board_rank | double precision, nullable | Order on the project's board, lower first; null (never moved) comes last, by id. See §10 |
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

### 5.3 Moving an issue to another project
The issue row itself moves: same id (so comments, attachments, history, watchers and links
stay attached), new `project_id` and the next `number` of the new project, `board_rank` reset.
Components and versions are mapped by name (case-insensitive) to those of the new project and
dropped otherwise; an assignee who is not `ASSIGNABLE_USER` there is removed; an issue leaves
its epic. One change group on the moved issue records `project`, `key`, the dropped values and
a `link` item, and notifies its watchers.

In the old place a **stub** is inserted with the old number: same type, summary and priority,
the last `DONE` status of its workflow, resolution *Moved* (the default resolution if that was
removed), a comment "Moved to NEW-12." and an issue link of type *Moved* (moved to / moved
from) to the moved issue, and `issue.moved_to_id` pointing to it. The stub is written without
notifications. So the old key keeps resolving — to the stub, which points on. A stub (one with
`moved_to_id`) has no workflow transitions and cannot be moved; when the moved issue is
deleted, `moved_to_id` becomes null and the stub is an ordinary closed issue. Mail answers that
are found by the key in their subject follow `moved_to_id`. An issue with an e-mail contact gets
the mailbox of the new project (`issue_mail_contact.mailbox`), if that project has one.

Resolutions have a `selectable` flag: *Moved* (seeded with `selectable = false`) is set only by
moving and is not offered or accepted in transitions.

Only standard issues without sub-tasks can be moved (parent and child are always in the same
project). It needs `DELETE_ISSUE` in the old project and `BROWSE_PROJECT` + `CREATE_ISSUE` in
the new one; neither may be archived, and the new project's workflow must contain the issue's
status.

`issue_key_history(project_id, number, issue_id)` with unique `(project_id, number)` — old keys
that resolve to another issue. Not written by moving (the stub holds the old key); kept for
redirects without a stub.

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

`status.category` ∈ `TODO`, `IN_PROGRESS`, `DONE` — used for colouring, and for the columns of the default board.

## 6. Collaboration

### `comment`
`(id, issue_id, author_id, body (Markdown), created_at, updated_at, update_author_id, version, search_vector)`

A new comment sets the issue's `updated_at` but not its `version`, so it never makes a
concurrent edit of the issue fail. Editing or deleting needs `EDIT_/DELETE_ALL_COMMENTS`, or the
`_OWN_` variant for one's own comments.

*(later)* `visibility_role_id` / `visibility_group_id` for restricted comments; the
columns can be added without changing existing rows.

### `attachment`
`(id, issue_id, author_id, file_name, media_type, size_bytes, sha256, storage_key, created_at)`

File contents are stored outside the database through a `BlobStore` interface. v1
implementation: local filesystem, `<data-dir>/attachments/<xx>/<storage_key>`, where
`storage_key` is a random 32-hex-digit UUID and `xx` its first two digits (to keep
directories small). A file is written to a temporary name and moved into place atomically;
`sha256` is computed while it streams. Files are never changed. They are deleted only after
the transaction that deletes their attachment (or its issue or project) commits, and a
failed upload removes the files it wrote. *(later)* S3-compatible storage.

Only PNG, JPEG, GIF and WebP are shown inline; everything else is served as a download with
`X-Content-Type-Options: nosniff` and a sandboxing `Content-Security-Policy`, so uploaded
HTML or SVG cannot run scripts in the application's origin. `media_type` is derived from
the file name, with the type the browser declared as fallback.

### `issue_link_type` and `issue_link`
- `issue_link_type(id, name, outward_description, inward_description)` — seeded: Blocks
  (blocks / is blocked by), Relates (relates to / relates to), Duplicate (duplicates / is
  duplicated by), Cloners (clones / is cloned by).
- `issue_link(id, link_type_id, source_issue_id, target_issue_id, created_at)` — unique
  `(link_type_id, source_issue_id, target_issue_id)`.

### `watcher`
`(issue_id, user_id, created_at)` — PK `(issue_id, user_id)`. Reporter, creator and commenters are added automatically.
Everyone who can browse an issue may watch it; seeing who watches and adding or removing others requires `MANAGE_WATCHERS`.

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
    `fixversion`, `parent`; from the workflow step: `status`, `resolution`; for issue links:
    `link` (value = the other issue's key, text = the phrase from this issue's side, e.g.
    "blocks BUG-2"; recorded on both issues); `project` and `key` when an issue moved to another
    project (§5.3). These match Jira's field ids where possible, so
    imported history needs little mapping.
  - Single-valued fields produce one item per change. `labels` produces one item with the full
    old and new label list (space-separated). `component`, `version` and `fixversion` produce one
    item per added or removed value, as Jira does.
  - Updates that change nothing produce no change group and leave `updated_at` untouched.
  - `field_kind` ∈ `SYSTEM`, `CUSTOM` (later).
  - `*_value`: machine value (ID or raw value); `*_text`: human-readable value at the time of change.

This mirrors Jira's `changegroup` / `changeitem` structure, so imported history maps
one-to-one. History rows are append-only: never updated, only deleted together with
their issue.

Comments are not tracked in `change_item` (they carry their own timestamps). Attachments
are: adding one records field `attachment` with `new_value` = attachment id and
`new_string` = file name; deleting one records the same in `old_value` / `old_string`, so
the history stays complete after the file is gone.

### 8.2 Import principles in the model
1. **Explicit timestamps and authors.** All `created_at` / `updated_at` / `resolved_at` and
   author columns are set by the application, never by database defaults, so an importer
   can supply original values.
2. **Placeholder users.** Unknown authors become `user_account` rows with
   `placeholder = true, active = false`. *(later)* An admin can merge a placeholder into a real account.
3. **External references.**
   `external_ref(id, import_source_id, source_type, source_id, local_type, local_id, imported_at)` —
   unique `(import_source_id, source_type, source_id)`. Makes imports repeatable and
   incremental, and allows links, parents and history to be resolved after all issues exist.
   `source_type` ∈ `user`, `issue`, `comment`, `attachment` (Forgejo: the attachment UUID;
   GitHub: the attachment's URL; GitLab: `<secret>/<file name>` of the upload; Jira and
   Redmine: the attachment id; Trac: `<ticket>/<file name>`). A
   reference counts only while its local row exists, so issues of a deleted project can be
   imported again.
4. **Sources and jobs.**
   - `import_source(id, kind, base_url, project_path, label, created_at)` — one per tracker,
     server and project there (e.g. `FORGEJO`, `https://codeberg.org`, `forgejo/website`, or
     `GITHUB`, `https://github.com`, `LibrePlan/LibrePlan`);
     unique `(kind, lower(base_url), lower(project_path))`.
   - `import_job(id, import_source_id, project_id, status, options, created_by, created_at,
     finished_at, counters…, message, status_text, waiting_until)` — `status` ∈ `RUNNING`, `SUCCEEDED`, `FAILED`,
     `CANCELLED`, `INTERRUPTED`; `options` is the JSON of the wizard's choices. **Credentials
     are never stored**; the access token is only kept in memory. `status_text` and
     `waiting_until` describe what a running job does now (for the status line), e.g. waiting
     for a rate limit.
   - `import_job_log(id, job_id, at, level, message)` — `level` ∈ `INFO`, `WARN`, `ERROR`.
5. **Origin of an issue.** `issue.origin_source_id`, `origin_key` (e.g. `forgejo/website#12`,
   or `ABC-12` for Jira, where `project_path` is the project key; Redmine: `web#12`, with the
   project identifier and the issue number, which is the same in all projects of a Redmine;
   Trac: `project#12`, with the name of the environment)
   and `origin_url`. Imported issues get new numbers; the origin is shown on the issue and
   recorded in its history as field `import` (value = original URL, text = e.g.
   "Forgejo forgejo/website#12").
6. **External links.** `issue_remote_link(id, issue_id, url, title, relation, created_at)` —
   `relation` ∈ `PULL_REQUEST`, `OTHER`; unique `(issue_id, url)`. Recorded in history as
   field `remotelink`.
7. **No notifications.** Importers write history with `IssueHistory.record`, which does not
   create outbox events, so nobody is mailed about imported issues.
8. **Read-only.** All importers use `ReadOnlyHttp`, which can only send GET requests, and only
   sends the token to the hosts of the source. Its one other request is an XML-RPC call (Trac),
   a POST it writes itself, only for the methods in `READ_ONLY_RPC_METHODS`. The engine (`IssueImporter`) is the same for
   every kind; a `Connector` per kind turns its API into the neutral model of `Source`.
9. **Hierarchy.** Sub-issues (GitHub; Jira's sub-tasks and the issues in an epic; Redmine's
   subtasks) become
   sub-tasks of a standard issue, or children of an epic; anything deeper or across projects
   becomes a *Relates* link. Other links between issues (GitLab's "relates to", Jira's link
   types, Redmine's relations) become links of the link type with the same name, else *Relates*, once per pair and
   type. A label can be mapped to an epic type, and to a component of the project (created when
   missing).

## 9. Search (PostgreSQL only)

- `issue.search_vector` — generated column:
  `setweight(to_tsvector('simple', summary), 'A') || setweight(to_tsvector('english', summary), 'A') ||
   setweight(to_tsvector('simple', coalesce(description, '')), 'B') || setweight(to_tsvector('english', coalesce(description, '')), 'B')`
- `comment.search_vector` — generated from `body`, weight `C`, same two configurations.
- GIN index on both `search_vector` columns.
- `pg_trgm` GIN index on `issue.summary` for partial and typo-tolerant matching.
- Text search: the words of the search text (letters and digits only, at most 8) become a
  `simple` tsquery in which every word is a prefix (`migr:*`) and all words must match. An issue
  matches when its own vector or any comment's vector matches, when its summary contains the
  text (`ILIKE`, served by the trigram index), or when the text is its key. Ranking: an exact
  key match first, then `ts_rank` on the issue's vector.
- Structured filters (project, type, status, priority, assignee, reporter, resolution, labels,
  created/updated dates) are plain SQL with B-tree indexes on the filtered foreign keys and dates.
  Within one condition values are OR-ed, conditions are AND-ed. User conditions accept user ids,
  `me` (whoever runs the search) and `none`.
- Every search is limited to projects the user may browse (`BROWSE_PROJECT`), in the same SQL.
- The query is a JSON object (`IssueQuery`), sent with `POST /api/v1/issues/search` and stored as is
  in saved filters. In the web UI it is also encoded in the URL, so a search can be shared as a link.

Indexes are maintained transactionally by PostgreSQL; no re-indexing is ever needed.

## 10. Other tables

- `saved_filter(id, owner_id, name, description, query jsonb, shared_with text, created_at, updated_at)` —
  `query` holds the structured filter definition (`IssueQuery`, without empty parts); `shared_with` ∈
  `PRIVATE`, `LOGGED_IN`, `PROJECT` (with project_id; visible to everyone who can browse that project).
  Filter names are unique per owner (case-insensitive). Only the owner changes a filter; global
  administrators may also delete it.
  *(later)* a text query language stored alongside.
- `outbox_event(id, event_type, payload jsonb, created_at, processed_at, attempts, last_error,
  issue_id, actor_id)` — transactional outbox. Issue events are written in the same transaction as
  the change: `issue.created`, `issue.changed` (payload: the change items, written wherever history
  is saved), `issue.commented` (comment id) and `issue.deleted` (a snapshot of the issue and the
  recipients, since the issue is gone afterwards). `issue_id` / `actor_id` have no foreign keys.
  The notification dispatcher claims events with `FOR UPDATE SKIP LOCKED`, bundles them per
  (issue, actor) until `quiet-period` passed since the last one (at most `max-delay` after the
  first), and writes one mail per recipient. *(later)* webhooks as a second consumer, with their
  own processed marker.
- `mail_message(id, recipient_id, to_address, subject, html_body, text_body, status, attempts,
  next_attempt_at, last_error, created_at, sent_at)` — rendered mails; `status` ∈ `PENDING`,
  `SENT`, `FAILED`. Sent with exponential backoff; removed after `retention`.
- `language(code, name, enabled, is_default)` — languages mails are available in; seeded `en`
  (default) and `nl`. Exactly one default, which is always enabled. A user's `locale` is mapped
  to an enabled language (exact, then base language, then the default).
- `mail_template(template_key, language_code, subject, body, updated_at, updated_by)` — templates
  changed by administrators; without a row the built-in file `mail-templates/<lang>/<key>.html`
  is used (fallback: default language, then English). Bodies are HTML with `{{keyword}}`
  placeholders, replaced without any template logic; values are HTML-escaped, the result is
  cleaned with a jsoup safelist, and a plain-text part is derived from it.
- `user_preference(user_id, key, value)` — `mail.notifications` (default `true`) and
  `mail.own-changes` (default `false`).
- `application_setting(key, value jsonb)` — base URL, mail settings, installation defaults.
- `license_request(challenge pk, created_at, created_by, country_code, users, currency,
  yearly_total, company_name, contact_name, contact_email, contact_phone)` — quotation requests,
  mailed to sales@buggywuggy.org; `license(id, challenge unique fk, valid_until, activated_at,
  activated_by, reminder_sent_at)` — entered donation codes (the tables keep the older name
  "license"). A code is an Ed25519 signature (by the BuggyWuggy project) of the challenge and the
  last valid day; BuggyWuggy holds only the public key. The login page shows the support status
  (`/api/v1/donation`): NONE without any code, ACTIVE while one is valid, EXPIRED after the
  last one ended. A month before a code ends, one reminder goes to sales.
- `board(id, project_id unique nullable, swimlanes, resolved_days)` — kanban boards. The one row
  with `project_id = null` is the default board (set by administrators); a project without a
  row of its own uses it. `swimlanes` ∈ `NONE`, `ASSIGNEE`, `EPIC`, `PRIORITY` is the default
  lane division; `resolved_days` (0–365) how long resolved issues stay on the board.
  `board_column(id, board_id, name, position)` and `board_column_status(board_column_id,
  board_id, status_id)`, unique on (board, status): a status is in at most one column.
  Moving an issue between columns is a workflow transition, between lanes an ordinary field
  change (assignee, parent epic, priority), each with its usual permission and history. The
  order is `issue.board_rank`: a move places the issue halfway between its neighbours, after
  giving unranked issues ranks at the end; when there is no room left between two ranks, all
  ranks of the project are renumbered (steps of 1024) under a row lock on the project. A new
  place is recorded as a `rank` change item (`higher` / `lower`) without notification.

## 11. Planned later (designed for, not built)

| Feature | How it fits |
|---|---|
| Custom fields | `field_definition(id, key, name, type, …)` + `field_context` (project/issue type scope) + `issue_field_value(issue_id, field_id, text_value, number_value, date_value, option_id, user_id)`. System fields get rows in `field_definition` too, so field-level config (screens, required fields) treats both alike. |
| Configurable workflows | Admin UI on existing `workflow*` tables; add conditions/validators/post-functions. |
| Sprints | Scrum boards: `sprint`, `issue_sprint` and a backlog, on top of the kanban `board` tables (§10). |
| Restricted comments | Visibility columns on `comment`. |
| Webhooks | Consumers of `outbox_event`. |
| SSO / LDAP | `external_identity`; groups synced into `user_group`. |
