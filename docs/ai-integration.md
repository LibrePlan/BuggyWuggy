# AI Integration

Status: draft · Last updated: 2026-11-18 · See also: [domain-model.md](domain-model.md)

BuggyWuggy can be connected to an **AI server** that answers questions in natural language
from the knowledge in the issue history (retrieval-augmented generation), with references to
the issues it used and an indication of how reliable the answer is. Users ask through an AI
button in the web client.

The AI server is optional and a separate program. BuggyWuggy contains only the integration
described here: the button, the endpoints that forward questions, and a read-only database
interface from which an AI server reads issues and evaluates permissions. Any AI server that
implements §4 can be used. The BuggyWuggy project offers one as a commercial product (the
*BuggyWuggy AI Server*), but this interface does not depend on it.

## Contents

1. [Principles](#1-principles)
2. [Security model](#2-security-model)
3. [Overview](#3-overview)
4. [AI server API](#4-ai-server-api-implemented-by-the-ai-server)
5. [Database interface](#5-database-interface-schema-ai_read)
6. [Confidence](#6-confidence)
7. [Client API](#7-client-api-implemented-by-buggywuggy)
8. [Data protection](#8-data-protection)
9. [Open questions](#9-open-questions)

## 1. Principles

1. **Two programs.** The AI server is a separate process. It receives questions over HTTP
   (§4) and reads BuggyWuggy's data through a dedicated, read-only database schema (§5). It
   keeps its own index and logs in a database of its own.
2. **The AI server trusts the database, not its callers.** Who the user is and what the user
   may see is determined by the AI server itself, from BuggyWuggy's database, for every
   question. Nothing a caller claims about the user or their rights is believed (§2).
3. **The browser never talks to the AI server.** The web client calls BuggyWuggy, which
   forwards the question. The AI server needs to be reachable only from the BuggyWuggy server.
4. **Least privilege.** The AI server's database account can read only the schema `ai_read`:
   views with exactly the columns it needs and functions that evaluate permissions. It cannot
   see password hashes, two-factor secrets, sessions, tokens or mail, and cannot write.
5. **Permission rules live in one place.** The rules are BuggyWuggy's; the functions in
   `ai_read` are part of BuggyWuggy's migrations and tested against its own permission checks
   (§5.4). An AI server does not implement permission rules itself.
6. **Optional.** Without a configured AI server, BuggyWuggy behaves exactly as without this
   feature: no button, no tickets, no change rows, no extra load.
7. **Honest confidence.** The reliability indication is derived from measurable signals (§6)
   and presented as a probability only once user feedback supports it.

## 2. Security model

### 2.1 What is trusted

| Party | Trusted? | Why |
|---|---|---|
| BuggyWuggy's database | Yes | It is the source of truth for users, rights and issues. Whoever can write in it can read every issue anyway. |
| The AI server | Yes | Installed and configured by the administrator. |
| The web client | **No** | Open source and running in the user's browser; anyone can make a variant that sends anything. |
| Any HTTP caller of the AI server | **No** | Even with a valid API key its statements about users and rights are not believed. |
| Issue and comment texts | **No** | They can come from outside (inbound mail, the feedback form, imports) and may contain instructions aimed at the LLM (prompt injection). |

### 2.2 Layers

1. **Network.** The AI server listens on localhost or a private network only; TLS when it is
   not localhost. The database accepts the AI server's account only from its host
   (`pg_hba.conf`), with `scram-sha-256`, and TLS (`sslmode=verify-full`) when not local.
2. **API key.** BuggyWuggy authenticates to the AI server with an API key
   (`Authorization: Bearer …`). This only proves the call comes from the BuggyWuggy server;
   it gives no rights to any data by itself.
3. **Ask ticket — who the user is.** For every question BuggyWuggy writes a one-time ticket
   to its database (§5.2) and passes only the ticket to the AI server. The AI server looks the
   ticket up itself, through its own read-only connection, and takes the user from there.
   A ticket is bound to one purpose and one question, valid for 2 minutes, and used once. A
   caller with only the API key (or a leaked one) cannot invent a ticket: that needs write
   access to BuggyWuggy's database.
4. **Permission check — what the user may see.** The AI server evaluates the `ai_read`
   permission functions (§5.3) for the ticket's user: whether the user is active and may use
   the AI, which projects to search, and finally which of the found issues and comments are
   visible. Always on the live data, at the moment of the question — never on its own copy.
5. **Only visible text reaches the LLM.** The fragments given to the LLM are read fresh from
   `ai_read` after the visibility check. Text the user may not see never enters the prompt, so
   no prompt — injected or not — can make the LLM reveal it.
6. **Second line in BuggyWuggy.** Before passing the answer to the browser, BuggyWuggy checks
   the `sources` against its own permission service and stops the stream with
   `ai.sources.forbidden` when one fails.
7. **Safe rendering.** The answer is untrusted Markdown. The client renders it with the same
   sanitizer as comments, **without images**, and with links only to issues of this
   installation (other URLs are shown as text), so an injected answer cannot leak data through
   an image or link URL.

### 2.3 Consequences for the client variant attack

A modified web client can send any question, any `projectId` and any `contextIssueKey`.
BuggyWuggy only issues tickets for the user of the session (fully authenticated, including
two-factor login where required), and the AI server only uses the rights of that ticket's
user. A `projectId` from the client can only narrow the search, never widen it.

## 3. Overview

```mermaid
sequenceDiagram
    participant C as Web client (untrusted)
    participant B as BuggyWuggy
    participant D as BuggyWuggy DB
    participant A as AI server
    participant L as LLM

    Note over A,D: Indexing (continuous, read-only)
    A->>D: ai_read.changes_after(cursor)
    A->>D: select from ai_read.issue / comment
    A->>A: index in own database

    Note over C,L: A question
    C->>B: POST /api/v1/ai/ask (session)
    B->>D: insert ai_ticket (user, purpose, question hash)
    B->>A: POST /ai/v1/ask (ticket, question)
    A->>D: ai_read.ticket → user; ai_read.user_access(user)
    A->>D: ai_read.browsable_projects(user)
    A->>A: search own index → candidates
    A->>D: ai_read.visible_issues / visible_comments(user, ids)
    A->>D: read fragments of visible items
    A->>L: question + visible fragments
    L-->>A: answer (stream)
    A-->>B: SSE: sources, delta…, done
    B->>B: re-check sources
    B-->>C: SSE
```

**Conventions.** HTTP: JSON with UTF-8, camelCase, timestamps ISO-8601 in UTC; errors as
RFC 9457 `application/problem+json` with a `code` (a message key such as `ai.unavailable`);
streaming as Server-Sent Events. AI server endpoints under `/ai/v1/…`. Additive changes keep
the version; receivers ignore unknown properties. Issue and comment ids are BuggyWuggy's
numeric ids (stable when an issue moves to another project; the key is not).

## 4. AI server API (implemented by the AI server)

Base path `/ai/v1`. Called only by BuggyWuggy, with `Authorization: Bearer <API key>`. The
API key is issued by the AI server and entered in BuggyWuggy's administration
(*Administration → AI*), where it is stored encrypted like other secrets.

### 4.1 `GET /ai/v1/status`

No user data; used to decide whether to show the AI button and on the administration page.

```json
{
  "apiVersion": 1,
  "product": "BuggyWuggy AI Server",
  "serverVersion": "1.0.0",
  "ready": true,
  "reason": null,
  "index": { "issues": 18234, "lastSyncAt": "2026-11-18T14:02:51Z", "state": "UP_TO_DATE" },
  "languages": ["en", "nl"],
  "details": { }
}
```

- `ready`: questions can be answered. When `false`, `reason` is a message key the client
  translates (`ai.index.empty`, `ai.model.loading`, `ai.license.invalid`,
  `ai.schema.unsupported`, …) and the AI button is shown disabled.
- `index.state` ∈ `EMPTY`, `INITIAL_LOAD` (with `progress` 0–1), `CATCHING_UP`, `UP_TO_DATE`,
  `ERROR` (with `message`).
- `details`: free-form key/value pairs (models, license, whether data leaves the server),
  shown as is on the administration page.

### 4.2 `POST /ai/v1/ask`

```json
{
  "ticket": "aiT_6f1c2a9e3b0d4c1e9a770d2b5c1e8f40c3d1…",
  "question": "Waarom mislukt de import van Jira-bijlagen met een 403?",
  "language": "nl",
  "projectId": 7,
  "contextIssueId": 9812,
  "options": { "maxSources": 6, "stream": true }
}
```

| Property | Notes |
|---|---|
| `ticket` | Ask ticket (§5.2) with purpose `ASK`. The **only** source of the user's identity. |
| `question` | 1–2000 characters, plain text. Its SHA-256 must equal the ticket's `subject_hash`. |
| `language` | The language of the answer. Not security-relevant. |
| `projectId` | Optional narrowing to one project. Intersected with `ai_read.browsable_projects`; a project the user may not browse gives an empty search, not an error. |
| `contextIssueId` | Optional: the issue that was open when the button was pressed. Used only if `ai_read.visible_issues` returns it for the user. |
| `options.maxSources` | 1–10, default 6. |

The AI server rejects the request with `403 ai.ticket.invalid` when the ticket is unknown,
expired, already used, for another purpose or another question, and with
`403 ai.user.forbidden` when `ai_read.user_access` says the user is inactive or lacks
`USE_AI`. The answer never says *why* a source was left out.

**Response with `stream: true`** — `text/event-stream`, events in this order:

```
event: queued
data: {"position":2}

event: sources
data: {"sources":[{"issueId":9640,"issueKey":"IMP-311","summary":"Jira attachments: 403 with API token","status":"Resolved","resolution":"Fixed","resolvedAt":"2026-09-02T10:11:00Z","relevance":0.83,"fragments":[{"commentId":55120,"text":"De Jira Cloud API geeft 403 als …"}]}]}

event: delta
data: {"text":"Dit is een bekend probleem: "}

event: done
data: {"answerId":"a_01JD3…","confidence":{…},"citations":[{"issueId":9640,"marker":1}]}
```

- `queued` (optional, repeated): the question waits for the model.
- `sources` comes before the answer. `fragments[].commentId` is `null` for a fragment of the
  summary or description.
- The answer is Markdown and refers to sources with `[1]`, `[2]`, matching
  `citations[].marker` (numbered in the order of `sources`). Markers that do not refer to a
  source are removed by the AI server.
- If nothing relevant and visible is found, `sources` is empty and the answer says so.
- `confidence`: see §6.
- On an error halfway: `event: error` with a problem object; the stream ends.

**Response with `stream: false`** — one JSON object with `answerId`, `answer`, `sources`,
`citations` and `confidence`.

**Other errors:** `400 ai.question.invalid`, `401` (wrong API key), `429 ai.busy` (queue
full, or the user's rate limit in the AI server, with `Retry-After`), `503 ai.unavailable`,
`504 ai.timeout`.

### 4.3 `POST /ai/v1/answers/{answerId}/feedback`

```json
{ "ticket": "aiT_…", "rating": "UP", "remark": "Klopte, opgelost met nieuw token." }
```

Ticket purpose `FEEDBACK`, `subject_hash` = SHA-256 of the `answerId`. The AI server accepts it
only when the ticket's user is the user who asked the question. `rating` ∈ `UP`, `DOWN`;
`remark` optional, at most 1000 characters. A new rating replaces the previous one.
Response `204`.

### 4.4 `POST /ai/v1/index/rebuild`

Body `{ "ticket": "aiT_…" }`, purpose `ADMIN`, `subject_hash` = SHA-256 of `rebuild`. The AI
server checks `ai_read.user_access(user).administer`. Starts a full re-index; response `202`
with the `index` object of §4.1.

## 5. Database interface (schema `ai_read`)

BuggyWuggy's Flyway migrations create a schema `ai_read` with views and functions. It is the
only part of BuggyWuggy's database an AI server may use, and it is versioned:
`ai_read.interface_version()` returns `1`; an AI server refuses to start (`ready: false`,
`ai.schema.unsupported`) on a version it does not know. Changes within a version are additive.

### 5.1 Views — issue data

Views run with the rights of their owner (BuggyWuggy's schema owner), so the AI account needs
no rights on the underlying tables.

| View | Columns |
|---|---|
| `ai_read.project` | `id, key, name, archived` |
| `ai_read.issue` | `id, project_id, number, issue_key, type, status, status_category, resolution, priority, parent_id, reporter_id, assignee_id, summary, description, created_at, updated_at, resolved_at` |
| `ai_read.comment` | `id, issue_id, author_id, body, created_at, updated_at, visibility` |
| `ai_read.issue_label` | `issue_id, label` |
| `ai_read.issue_component` | `issue_id, component` |
| `ai_read.issue_version` | `issue_id, version, kind` (`AFFECTS` / `FIX`) |
| `ai_read.issue_link` | `source_issue_id, target_issue_id, link_type, outward, inward` |

- Users appear only as ids: no names, e-mail addresses, password hashes or anything else from
  `user_account`. Attachments are not exposed.
- `comment.visibility` is always `null` for now, for restricted comments (planned, see
  [domain-model.md §11](domain-model.md#11-planned-later-designed-for-not-built)).
  An AI server must not index comments with a non-null value; visibility is decided by
  `ai_read.visible_comments` anyway.
- These views are for **indexing**. Reading them says nothing about who may see the data.

### 5.2 Tickets and changes

**`ai_ticket`** (table, written by BuggyWuggy):
`ai_ticket(id, token_hash bytea unique, purpose, subject_hash bytea, user_id, created_at, expires_at)`

- `purpose` ∈ `ASK`, `FEEDBACK`, `ADMIN`; `subject_hash` binds the ticket to one question,
  answer id or action (§4).
- The token is 32 random bytes (base64url, prefix `aiT_`); only its SHA-256 is stored.
- Written only for a fully authenticated session (with `TWO_FACTOR_VERIFIED` where two-factor
  login is on), only for a user with `USE_AI` (or `ADMINISTER` for `ADMIN`), and committed
  before the AI server is called. Valid for 2 minutes; expired rows are removed hourly.
- View `ai_read.ticket(token_hash, purpose, subject_hash, user_id, expires_at)`, only
  unexpired rows. The AI server looks up by `token_hash`, and keeps the ids of used tickets in
  its own database, so a ticket works once.

**`integration_change`** (table, written by BuggyWuggy in the same transaction as the change,
next to where history and outbox events are written; only while an AI server is configured):
`integration_change(seq bigint identity, xid xid8, kind, issue_id, project_id, at)`

| `kind` | Meaning |
|---|---|
| `CHANGED` | The issue, or one of its comments, labels, components, versions or links, was created, changed or deleted, or the issue moved to another project |
| `DELETED` | The issue was deleted |
| `PROJECT_CHANGED` | Project renamed, re-keyed, archived or unarchived |
| `PROJECT_DELETED` | Project and all its issues were deleted |

Read through `ai_read.changes_after(after_seq bigint, max_rows int)`, which only returns rows
whose transaction is older than every transaction still running
(`xid < pg_snapshot_xmin(pg_current_snapshot())`). A plain sequence is not a safe cursor — a
transaction with a lower number can commit after one with a higher number — this rule makes
sure a cursor never skips a row. Rows older than 30 days are removed; an AI server whose
cursor is older than `ai_read.changes_oldest_seq()` does a full re-index. Permission changes
produce no rows: they do not change what is indexed, and visibility is checked per question.

### 5.3 Functions — permissions

All are `stable`, `security definer` with a fixed `search_path`, owned by BuggyWuggy's schema
owner; `execute` is revoked from `public` and granted to the AI role only.

| Function | Returns |
|---|---|
| `ai_read.user_access(user_id)` | One row `(active boolean, use_ai boolean, administer boolean)`; all `false` for unknown, inactive and placeholder users. |
| `ai_read.browsable_projects(user_id)` | `setof bigint`: projects the user may browse. A pre-filter for the search. |
| `ai_read.visible_issues(user_id, issue_ids bigint[])` | `setof bigint`: the given issues that exist and the user may see. |
| `ai_read.visible_comments(user_id, comment_ids bigint[])` | `setof bigint`: the given comments that exist, whose issue the user may see, and whose visibility (later) allows it. |

`visible_issues` and `visible_comments` are the final word; `browsable_projects` only makes
the search efficient. When BuggyWuggy's rules gain issue-level conditions (issue security,
`REPORTER`/`ASSIGNEE` browse grants, restricted comments), they are added to these functions
in the same release, and the AI server needs no change.

### 5.4 Keeping the rules in one place

The functions are written from the same SQL conditions as `ProjectPermissionService`
(`PROJECT_BROWSABLE`). BuggyWuggy's test suite runs a matrix of users, groups, roles, schemes
and projects through both the Java permission checks and the `ai_read` functions and fails
when they disagree. Any change to permission rules must update both; the domain model lists
`ai_read` among the places to check.

### 5.5 Database account

Set up once by the administrator (not by Flyway, which normally lacks `CREATEROLE`).
BuggyWuggy's administration page shows these statements with the right names:

```sql
create role buggywuggy_ai login password '…';
alter role buggywuggy_ai set default_transaction_read_only = on;
alter role buggywuggy_ai set statement_timeout = '30s';
grant connect on database buggywuggy to buggywuggy_ai;
grant usage on schema ai_read to buggywuggy_ai;
grant select on all tables in schema ai_read to buggywuggy_ai;
grant execute on all functions in schema ai_read to buggywuggy_ai;
```

The account gets no rights on schema `public` or any table in it. Migrations that add views
or functions to `ai_read` grant them to the role `buggywuggy_ai_read` (created by the
migration as `nologin` if it does not exist); the statements above may also simply
`grant buggywuggy_ai_read to buggywuggy_ai`.

## 6. Confidence

```json
"confidence": {
  "level": "HIGH",
  "score": 0.78,
  "calibrated": false,
  "signals": ["STRONG_MATCH", "RESOLVED_SOURCES", "SOURCES_AGREE"]
}
```

| Property | Meaning |
|---|---|
| `level` | `HIGH`, `MEDIUM`, `LOW` or `NONE` (nothing relevant found). What the client shows. |
| `score` | 0–1. While `calibrated` is `false` a relative score, not a probability; the client does not show it then. |
| `calibrated` | `true` when the AI server has fitted `score` to user feedback (§4.3): `score` is then the estimated chance that a user rates the answer as correct, and the client shows it as a percentage. |
| `signals` | Codes explaining the level; the client translates the known ones and ignores others. |

| Code | Meaning |
|---|---|
| `STRONG_MATCH` / `WEAK_MATCH` | The best sources match the question well / poorly. |
| `RESOLVED_SOURCES` | The best sources were resolved as fixed or done. |
| `UNRESOLVED_SOURCES` | The best sources are still open: the problem is known, a solution may not be. |
| `SOURCES_AGREE` / `SOURCES_DISAGREE` | Several sources point to the same cause or solution / contradict each other. |
| `OLD_SOURCES` | The best sources are old. |
| `FEW_SOURCES` | Fewer than two relevant sources. |
| `ANSWER_GROUNDED` / `ANSWER_UNGROUNDED` | The claims in the answer do / do not appear in the cited fragments. |

How the level and score are computed is up to the AI server.

## 7. Client API (implemented by BuggyWuggy)

Endpoints for the web client, with BuggyWuggy's usual session authentication and CSRF.

| Endpoint | Behaviour |
|---|---|
| `GET /api/v1/ai/status` | `{ "enabled", "available", "reason" }`. `enabled`: an AI server is configured and the user has `USE_AI`. `available`: its status says `ready`; otherwise `reason`. Cached for 30 seconds. With `enabled: false` the client shows no button. |
| `POST /api/v1/ai/ask` | Body `{ "question", "projectId"?, "contextIssueKey"? }`. BuggyWuggy writes an `ASK` ticket for the session's user, calls §4.2 with it and passes the stream on after re-checking `sources` (§2.2, layer 6). Rate limit per user (default 20 questions per hour). |
| `POST /api/v1/ai/answers/{answerId}/feedback` | Body `{ "rating", "remark"? }`; BuggyWuggy writes a `FEEDBACK` ticket and calls §4.3. |
| `GET/PUT /api/v1/admin/ai` | Administration: AI server URL, API key (write-only), rate limit; shows the AI server's status and the database statements of §5.5. |
| `POST /api/v1/admin/ai/rebuild` | Writes an `ADMIN` ticket and calls §4.4. |

New global permission `USE_AI` (granted to group `users` by default), so an installation can
limit who may ask questions.

**Web client.** An AI button in the top bar opens a prompt window: a question field, an
optional project choice (default: all projects; the current project when opened from one) and,
on an issue page, the option to ask about that issue. The answer is shown as it streams in,
rendered as described in §2.2 (layer 7), with the sources as links to their issues, the
confidence level and its signals, and 👍/👎.

## 8. Data protection

- Through `ai_read` the AI server can read issue texts, comments and user **ids**; no names,
  e-mail addresses, attachments or account data. Names written in issue texts themselves are
  readable as they are.
- The AI server's own database holds copies of issue texts and must be protected (access,
  backups) like BuggyWuggy's database.
- The administration page states which AI server is connected and, from its status `details`,
  whether it sends data to an external service.
- When an issue is deleted, `ai_read.visible_issues` stops returning it at once, so it can no
  longer appear in an answer; the AI server removes it from its index through the change
  feed.

## 9. Open questions

1. Follow-up questions in a conversation (`conversationId`).
2. Whether attachments (text files, logs) should be exposed for indexing.
3. Whether BuggyWuggy's own Java permission checks should call the `ai_read` functions (or a
   shared SQL function), so there is literally one implementation.
