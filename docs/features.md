# BuggyWuggy Features

## Issue tracking

- **Projects with their own key** (e.g. `BUG`), and issues numbered per project (`BUG-123`). Old issue keys keep working after a project is re-keyed.
- **Issue types:** Epic, Story, Feature, Task, Bug and Sub-task. Work can be organised three levels deep: epic → issue → sub-task.
- **Fields:** summary, description, priority, status, resolution, assignee, reporter, labels, components, affects/fix versions and due date.
- **Built-in workflow:** Open → In Progress → Resolved → Closed, plus Reopen, with a resolution when work is done.
- **Full change history** for every issue, covering who changed what, when, and from what to what. The history is read-only.
- **Safe concurrent editing:** nobody overwrites someone else's changes by accident.
- **Moving issues to another project:** the issue gets a new key and keeps its comments, attachments, history and links; a closed issue with the old key points to it.

## Collaboration

- **Comments**, with editing, deleting and an "edited" marker.
- **Markdown** in descriptions and comments, with a formatting toolbar, keyboard shortcuts and a live side-by-side preview. It supports task lists, tables, code blocks, and automatic links to issue keys.
- **Attachments** by drag and drop, with image thumbnails and a preview. Images can be embedded in issue text, and risky file types are always downloaded rather than opened.
- **Issue links**, also across projects: blocks, relates to, duplicates, clones.
- **Watchers**, so people can follow the issues they care about.
- **E-mail notifications** for watchers, reporters and assignees. Changes made in quick succession are bundled into one mail, and users only get mail about what they are allowed to see.
- **E-mail to issue:** mailboxes (IMAP) per project turn incoming mail into issues, with attachments. Spam, auto-replies and mail loops are filtered out.
- **Replies by e-mail:** comments can be internal or sent to the external reporter by mail; their answer comes back as a threaded comment and reopens a resolved issue.
- **Split an answer** into a new issue (copy or move), linked to where it came from.
- **Editable mail templates** with a preview and test mail. Templates are included in English and Dutch, and each user receives mail in their own language.

## Search and planning

- **Fast full-text search** across summaries, descriptions and comments, with structured filters on project, type, status, priority, assignee, resolution, labels, reporter and dates.
- **Bookmarkable searches:** every search has its own URL that you can share.
- **Saved filters** that can be private, shared with everyone, or shared with the members of a project. "Current user" filters work for every person who opens them.
- **Kanban boards** for every project, with configurable columns and swimlanes by assignee, epic or priority. Drag and drop moves issues through the workflow, reassigns them or re-ranks them, and cards can also be moved with the keyboard.
- **TaskWarrior-style urgency score** for every issue. You can sort by it, and click it to see how it was calculated.

## Migration from other trackers

- **Importers for Jira (Cloud and Server/Data Center), GitHub, GitLab, Forgejo/Codeberg, Redmine, Trac and Bugzilla.**
- Original authors, dates, comments and history are kept, and each imported issue links back to where it came from.
- Imports are **strictly read-only** towards the source system, and access tokens are never stored.
- You can import into new or existing projects. Running an import again only adds new issues and comments.
- A live status line, including rate-limit waits, plus a downloadable log.

## Users, security and permissions

- **Users and groups**, with project roles (Users, Developers, Administrators) and global permissions.
- **Per-project access control:** projects you cannot access look exactly like projects that don't exist.
- **Two-factor login (TOTP)** with a setup wizard, recovery codes and an admin reset.
- **Single sign-on with OpenID Connect** (e.g. Keycloak), with optional automatic account creation and group mapping. Password login can be restricted or switched off.
- **Brute-force protection** on login.

## Administration and operations

- **Self-hosted:** a single executable JAR with PostgreSQL as the only dependency.
- **Guided first-time setup**, and a configuration file template generated on first start.
- **Web-based administration** for users, groups, permissions, default board, e-mail and imports. Projects can be archived, restored and deleted.
- **Complete REST API** with interactive OpenAPI documentation. The web interface uses the same API.
- **Personal API tokens** for scripts and other programs: read-only or not, with an optional last
  day, stored only as a hash, and removable by the user and by administrators.
- **Built-in help:** searchable user and administrator guides inside the application.
- **Software bill of materials (CycloneDX SBOMs)** for the server and the web UI.
- **Demo mode** for hosting a public try-it-out instance.

## Open source

- **Fully open source** under the AGPL-3.0-or-later license.
- **Optional annual donation**, priced per country (Big Mac index) and number of users. BuggyWuggy works fully without it.
- **Feedback form for supporters:** organisations that donate send compliments, bug reports, feature requests, service requests and training requests straight to the developers, with priority. The mail carries all details as a JSON attachment as well. Everyone else can report issues publicly in the source code repository.
