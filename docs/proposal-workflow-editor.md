**Type:** Feature
**Summary:** Configurable statuses and workflows for administrators

## Problem

BuggyWuggy has one fixed workflow: Open → In Progress → Resolved → Closed, plus Reopened. Teams that work differently (a review step, "Waiting for customer", "Ready for test") can only add a status by editing the database by hand: `status`, `workflow_status` and `workflow_transition`. That bypasses validation, is not in the history, and is not possible through the API.

The data model already supports more: statuses, workflows, transitions and workflow schemes (per issue type) are stored as data (`docs/domain-model.md` §7.1). Only an admin UI and API on top are missing.

## Proposal

### Phase 1: statuses and the transitions of a workflow

**Administration → Statuses**
- List all statuses with name, description, category (To Do / In Progress / Done) and order.
- Create a status, edit it, and change the order.
- Deleting a status is only possible when no issue, workflow or board column uses it. Otherwise the Delete button is disabled, with a "?" that explains why and where the status is used.

**Administration → Workflows → Default**
- Choose which statuses are part of the workflow, and which one is the initial status (exactly one).
- Transitions as a table: name, from status (or "any status"), to status, and order. The order sets the order of the workflow buttons on an issue.
- Adding a transition with the same name as an existing one makes it the same button from another status (as "Resolve Issue" works now).
- Checks before saving:
  - exactly one initial status;
  - every status can be reached from the initial status;
  - at least one status of category Done can be reached;
  - no transition from a status to itself;
  - a status that issues still have cannot be removed from the workflow (disabled, with "?" and the number of issues). In phase 2 this opens the status remapping instead.
- The resolution rules stay as they are: entering a Done status asks for a resolution, leaving it clears the resolution.

**Board**
- A new status is shown on the default board in the column of its category, until the administrator places it elsewhere.
- A project board with its own columns shows a notice for statuses that are in no column: "These issues are not on the board: …".

**REST API**
- Endpoints for statuses and for the transitions of a workflow, with the same checks. Only for BuggyWuggy administrators.
- `web/src/api/schema.d.ts` updated.

**History**
- Changes to statuses and workflows are logged (who, when, what).

### Phase 2: more than one workflow

- Create workflows (also as a copy of an existing one), rename them, delete them when unused.
- Workflow schemes: which workflow applies to which issue type. Choosing the scheme of a project is done in its project settings, by BuggyWuggy administrators.
- Importers: in the mapping step, a status that does not exist yet can be created directly.

### Status remapping

Today nothing checks the statuses when a workflow changes. An issue whose status is not in its workflow has no workflow buttons, apart from transitions allowed from any status, and may fall outside every board column. Moving an issue to another project, or changing its issue type, is refused when the new workflow lacks the issue's status (`issue.move-workflow`, `issue.type-change-workflow`). The user then has to change the status first. Remapping replaces both: missing statuses are mapped, never left behind.

**When it applies.** Whenever issues get another workflow:
- the workflow scheme of a project changes;
- an entry of a workflow scheme changes (another workflow for an issue type), for every project using that scheme;
- a status is removed from a workflow that issues are in;
- an issue is moved to another project (move dialog);
- the issue type of an issue changes.

**The mapping screen.** It works like the mapping step of the import wizard:
- One row per (old workflow, status in use, issue type) whose status is missing from the new workflow. The row shows the number of issues, with a link to the list of those issues.
- A drop-down with the statuses of the new workflow. A suggestion is preselected: the status with the same name (ignoring case), otherwise the initial status for To Do, or the first status of the same category for In Progress and Done.
- Statuses that exist in both workflows are shown collapsed as "kept" and need no choice.
- A summary before applying: "37 issues change status, 0 are left without a mapping". **Apply** is disabled, with a "?" that explains why, until every row has a target.
- When a project has issues that are in no row at all, the screen says so. An example is issues whose status was already invalid before the change, after a manual database edit; they get a mapping row too.

**Resolution.** It follows the same rules as the workflow buttons:
- mapped to a Done status from a non-Done one: a resolution is required. It is chosen per row, with the default resolution preselected;
- mapped from Done to a non-Done status: the resolution and `resolved_at` are cleared;
- Done to Done: the resolution and `resolved_at` are kept.

**Applying.**
- Everything happens in one transaction: the workflow change and all status changes succeed together, or nothing changes.
- Every changed issue gets a history entry: *Status: Waiting for Customer → In Progress (workflow changed to Support)*, by the administrator who applied it.
- No notification mail per issue. Watchers are not mailed for a bulk change, but the history shows it.
- For large projects, the work is done in batches with a progress line, like the import status line, without blocking the UI.
- Webhooks get one event per changed issue (`issue.updated`, with the status change), like other changes.

**Boards.** After the change, the board settings of the affected projects show the statuses that are in no column yet, with **Place in the column of their category** as a one-click fix.

**API.** The same in two steps:
- `POST …/workflow-change/preview` returns the rows with their suggestions.
- `POST …/workflow-change` with the chosen mapping applies it. Missing or invalid mappings return 400 with the rows that are wrong.

**Moving an issue and changing its type** use the same mapping, for one issue:
- The move dialog already shows "what changes on the way". When the status is missing in the target workflow, it shows a status drop-down there, with the suggestion preselected, instead of refusing.
- Changing the issue type does the same in a small dialog.

### Out of scope

Transition conditions, validators, post-functions and screens (`docs/domain-model.md` §7.1, "later").

## Acceptance criteria (phase 1)

- An administrator can add the status "In Review" (In Progress), add transitions to and from it, and the new buttons appear on issues without a restart.
- Invalid workflows cannot be saved; the error says what is wrong.
- A status in use cannot be deleted. The button is disabled and explains why.
- Existing installations keep their current statuses and transitions. The Default workflow simply becomes editable.
- Everything in the UI is also possible through the API.
- The user guide (*Moving through the workflow*) and the installation guide (*Administration*) describe it.

## Acceptance criteria (phase 2)

- Switching a project to a workflow without "Reopened" shows a mapping row for Reopened, with the number of issues and a preselected suggestion. Apply is only possible once every row has a target.
- After applying, no issue in the project has a status outside its workflow. Each changed issue has a history entry, and no notification mails were sent.
- A failure halfway (e.g. the database stops) leaves the project on its old workflow with all statuses unchanged.
- Mapping to and from Done statuses sets and clears the resolution as the workflow buttons do.
- Moving an issue to a project whose workflow lacks its status, or changing its type to one with such a workflow, asks for the new status instead of refusing.
- The preview and apply endpoints give the same result as the screen.
