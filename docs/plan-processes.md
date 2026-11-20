# Processes: steps done by different people

Status: proposal · Last updated: 2026-11-20 · See also: [plan-test-runs.md](plan-test-runs.md), [domain-model.md](domain-model.md)

Reusable process definitions whose steps become sub-tasks for different people or groups, each step starting when the steps it depends on are done.

## Goal

- Users with the permission **Process creator** describe a process for a project: a set of steps, each done by a user or a group, and which steps wait for which other steps.
- A user starts a process by creating an issue of type **Process** and choosing the process definition. Each step becomes a sub-task of that issue.
- A step becomes someone's turn only when the steps it waits for are done. Until then it does not show up in their lists and they get no mail about it.
- Steps can run in parallel and come together again (branches). The flow is shown as a tree with indents and arrows.
- Steps can be hidden from people who have no business seeing them (high security environments).
- When something goes wrong (a step is rejected, a test fails or a deadline passes), the process **escalates** to the person who started it: clearly shown, and mailed.

## Concepts

| Name | What it is |
|---|---|
| **Process definition** | The template: name, description and its steps. Belongs to one project. Managed by process creators. |
| **Definition step** | One step of a definition: summary, description, who does it, which steps it waits for, optional duration, optional test case, hidden or not. |
| **Process run** | An issue of type *Process*, started from a definition. Its **initiator** is the user who started it. |
| **Step** | A sub-task of the process run, made from a definition step. It has a **state**: *waiting*, *active*, *done*, *skipped* or *escalated*. |
| **Assignee group** | New for every issue: a group whose members may take the issue. See *Assigning to a group*. |

A run copies the definition when it starts, like a test run copies its test case. Editing a definition never changes runs that already exist.

Tests and processes stay separate: each has its own definitions, tables and code. The only connection is that a step can *be* a test run (see *Steps that are tests*). The process asks the tests module whether that run has passed; the tests module knows nothing about processes.

## Assigning to a group

This is a general change to issues, also useful outside processes (for example a support desk). It is built first.

Today an issue has at most one assignee, a user. Every issue gets a second, optional field: the **assignee group**.

| Assignee group | Assignee | Meaning |
|---|---|---|
| — | Jan | As now: Jan does it. |
| Finance | — | **In the queue** of Finance: any member may take it. |
| Finance | Piet | Piet, a member of Finance, has **taken** it. The group stays, so it can go back to the queue. |

- **Take:** a member of the group presses **Take** and becomes the assignee. **Release** puts the issue back in the queue. Both are history entries.
- **Who may take:** members of the group who may be assigned in the project (`ASSIGNABLE_USER`). Others see **Take** disabled with a "?".
- **Setting the group:** holders of `ASSIGN_ISSUE`, like the assignee. Only groups with at least one member who may be assigned in the project can be chosen.
- **My issues** gets a second section, *Waiting in my groups*: the queue issues of all your groups.
- **Mail:** when an issue enters a group's queue, every member gets a mail. Once someone has taken it, only the assignee is mailed, as now.
- **Board:** the swimlanes by assignee get a lane per group queue.
- **Search:** a filter *Assignee group*, and *In my groups' queues*.
- **API and webhooks:** `assigneeGroup` next to `assignee` in issue requests, responses and webhook payloads.
- **Deactivated users and deleted groups:** a deleted group clears the assignee group of its issues (history entry); a member leaving the group keeps an issue they already took.

## Hidden steps

A definition step can be marked **hidden**. Its step in a run is then visible only to:

- the **initiator** of the run;
- the step's **assignee** and the members of its **assignee group**;
- holders of the new project permission **View all process steps** (`VIEW_PROCESS_STEPS`), for example auditors.

Nobody else sees it, even when they may browse the project.

This is the first access check in BuggyWuggy **per issue** instead of per project, so it must hold everywhere. One central SQL fragment, `ISSUE_VISIBLE` (next to the existing `ProjectPermissionService.PROJECT_BROWSABLE`), combines both checks, and every query that returns issues or anything of an issue uses it:

- the issue page and every issue list;
- search, filters and saved filters, including the counts ("37 issues");
- the board;
- links between issues, the parent's sub-task list, and the issue picker;
- history, comments and attachments (including the download of an attachment);
- mail: no notification about a hidden step to anyone outside the list above;
- webhooks: events about a hidden step carry only its key and state, no summary, description or comments;
- the REST API, which uses the same services.

Where a hidden step is part of something the user may see, it keeps its place but shows no content: in the process view it is shown as *Step 3: no access rights*, with only its state. A direct link to a hidden step answers *not found*, the same as for an issue that does not exist, so its existence does not leak either.

## Data model (migration V30, after V29 of tests)

**General**
- `issue.assignee_group_id` (FK user_group, on delete set null, nullable), with an index.
- **Issue type `Process`:** icon `process`, hierarchy level 0, added like `V19__feature_issue_type.sql`.
- **Project permissions** `MANAGE_PROCESSES` (*Process creator*) and `VIEW_PROCESS_STEPS` (*View all process steps*): added to `ProjectPermission` and to the check constraint `permission_grant_permission_ck`, both granted by default to the project role *Administrators*.

**Definitions**
- **`process_definition`:** `id`, `project_id` (FK, on delete cascade), `name`, `description` (Markdown), `auto_resolve boolean default false`, `archived boolean`, `created_by`, `created_at`, `updated_at`. Unique `(project_id, lower(name))`.
- **`process_definition_step`:** `id`, `definition_id` (FK, on delete cascade), `position`, `summary`, `description` (Markdown), `assignee_kind`, `assignee_user_id`, `assignee_group_id`, `hidden boolean`, `duration` (optional), `test_case_id` (FK test_case, optional).
  - `assignee_kind` is one of `USER` (a fixed user), `GROUP` (a group queue), `INITIATOR` (whoever starts the process) and `CHOSEN_AT_START` (the start dialog asks for a user or group).
- **`process_definition_dependency`:** `(step_id, depends_on_step_id)`, both FK `process_definition_step`, on delete cascade.

**Runs**
- **`process_run`:** `issue_id` (PK, FK issue, on delete cascade), `definition_id` (FK, on delete set null), `definition_name` (copy), `initiator_id` (FK user_account), `auto_resolve` (copy), `state` (`RUNNING`, `ESCALATED`, `COMPLETED`, `STOPPED`), `completed_at`.
- **`process_step`:** `issue_id` (PK, FK issue: the sub-task, on delete cascade), `run_issue_id` (FK process_run, on delete cascade), `definition_step_id` (on delete set null), `position`, `planned_assignee_user_id`, `planned_assignee_group_id`, `hidden`, `duration`, `test_case_id`, `state`, `activated_at`, `due_at`, `done_at`, `escalation` (`REJECTED`, `TEST_FAILED`, `OVERDUE`; null when not escalated), `escalated_at`.
- **`process_step_dependency`:** `(step_issue_id, depends_on_issue_id)`, both FK `process_step`.

States and escalation reasons are text with a check constraint, mapped to Java enums, as elsewhere.

The dependencies live in these tables, not as *Blocks* links between issues. A link can be removed by anyone who may edit links, which would break the process. The step page shows *Waiting for: …* from the process tables instead.

## Rules

### Process definitions
- Everyone who may browse the project can see its definitions; only `MANAGE_PROCESSES` holders can create, edit, copy, archive or delete them.
- A definition with runs cannot be deleted, only archived (Delete disabled with a "?"). Archived definitions cannot be started; existing runs continue.
- A new step waits for the previous step by default, so a simple list stays simple. The creator can choose other steps, several or none (parallel steps).
- Checks before saving: at least one step; at least one step that waits for nothing; **no loops** (A waits for B waits for A, also through other steps); a `USER` or `GROUP` step has its user or group, and that user or group may be assigned in the project.
- A step with a test case needs the tests feature; only test cases that are not archived can be chosen.

### Starting a run
- In the create dialog, the type *Process* adds a required field **Process**. When the definition has `CHOSEN_AT_START` steps, the dialog asks for their user or group.
- The type *Process* is disabled with a "?" when the project has no definitions.
- In one transaction, the server creates the process issue (reporter and initiator: the user), one sub-task per step, the run and its steps.
- Steps that wait for nothing **activate** right away. All other steps are **waiting**.

### Waiting and activation
- A **waiting** step has its planned assignee or group stored on the step, but not yet on the issue. So it does not appear in anyone's *My issues*, group queue, board or mail.
- The board and the issue lists leave waiting steps out by default; search can show them with the filter *Process step state*.
- A waiting step's workflow buttons are disabled, with a "?" that names the steps it waits for. The server refuses the transition too. Comments are allowed. This applies **only to process steps**: *is blocked by* links elsewhere in BuggyWuggy keep working as now.
- A step **activates** as soon as every step it waits for is *done* or *skipped*:
  - the planned user or group is set as assignee or assignee group (history entry *by the process*);
  - `activated_at` is set, and `due_at` when the step has a duration;
  - the assignee, or every member of the group, gets the mail *It's your turn: <step> in <process>*.

### Done, rejected, escalated
- An ordinary step is **done** when its issue enters a status of category *Done* with resolution *Done* or *Fixed*.
- Any other resolution (*Won't Fix*, *Duplicate*, *Cannot Reproduce*) is a **rejection**. The step becomes *escalated* with reason `REJECTED`.
- A **deadline** that passes (`due_at`) escalates the step with reason `OVERDUE`. The step stays with its assignee, who can continue, and is escalated only once.
- An escalation:
  - sets the run to `ESCALATED`;
  - shows a **red banner** on the process and on the step, with the reason;
  - colours the step red in the process view;
  - mails the initiator *Process escalated: <step> — <reason>* (or the project administrators, when the initiator is deactivated).

### The initiator
Only the initiator can change a running process:
- **Reopen** an escalated step (back to active; the issue is reopened);
- **Reassign** a step (user or group, also while it is waiting);
- **Skip** a step: it counts as done for the steps that wait for it (history entry with a reason);
- **Change a deadline**;
- **Add a step**, with the steps it waits for (no loops);
- **Stop** the process: open steps are closed with resolution *Won't Fix*, and the run becomes `STOPPED`.

When none of the steps are escalated any more, the run is `RUNNING` again.

**When the initiator is deactivated,** the project administrators (`ADMINISTER_PROJECT`) take over the initiator's role for that run: they can use the actions above, and they get the escalation mails. The process view shows *Initiator deactivated — handled by the project administrators*.

### Completion
- The run is `COMPLETED` when every step is *done* or *skipped*.
- With `auto_resolve`, the process issue then goes through a transition to a Done status with resolution *Done*. This happens when such a transition exists and the user whose action completed the run may transition the issue. Otherwise the run is only marked complete.
- Activation and completion run in the same transaction as the change that causes them, with a row lock on the run. Two steps finishing at the same moment then still activate a shared next step exactly once.

### Steps that are tests
- A definition step can have a **test case**. Its sub-task in a run gets a test run of that test case: the steps of the test appear on the step's issue.
- The step is **done** when its test run completes with outcome `PASSED`. A run that completes as `FAILED` escalates the step with reason `TEST_FAILED`.
- The process uses one call of the tests module: "create a test run on this issue" and "the outcome of this issue's run". It knows nothing else about tests.

### Other actions
- Process runs and their steps cannot be moved to another project, and their issue type cannot be changed (disabled with a "?").
- Deleting the process issue deletes its steps, as for every parent with sub-tasks.

## Deadlines

- A duration is optional per step: a number of hours or days in **calendar time**, counted from activation. Weekends and holidays count too.
- A scheduled job checks for overdue steps every minute. Several BuggyWuggy instances can share one database, so the job claims rows with `for update skip locked`, and every overdue step escalates once.

## History, notifications, webhooks

- History on the steps: activation, assignment by the process, Take and Release, skip, reassign, escalation.
- History on the process issue: start, each step done, escalations, the initiator's actions, completion or stop.
- New mail templates (editable like the others in Administration → E-mail):
  - *It's your turn*;
  - *Process escalated*;
  - *Process completed*.
- Webhooks:
  - `issue.updated` for the changes;
  - new events `process.step_activated`, `process.escalated`, `process.completed`.

  Hidden steps carry only key and state.

## The process view

- On the process issue, a panel **Process** shows the flow as a tree:
  - every step indented by its depth (the longest chain of steps it waits for);
  - arrows from the steps it waits for;
  - parallel steps at the same depth.
- Each step shows its state colour, assignee or group (planned while waiting, visible to everyone who may see the step), due date, and escalation in red. Hidden steps the user may not see are shown as *Step n: no access rights*, with only the state.
- At the top of every step issue: *Part of process <name> — view the flow*, which opens the same view with this step highlighted.
- The initiator sees the actions (reopen, reassign, skip, deadline, add step, stop) in the view. For others they are disabled with a "?".

## REST API

The API follows the existing conventions and is added to `web/src/api/schema.d.ts`. Everything goes through `ISSUE_VISIBLE`.

- `GET/POST /api/projects/{key}/process-definitions`, `GET/PUT/DELETE …/{id}`, `POST …/{id}/archive` and `/unarchive` (changes need `MANAGE_PROCESSES`).
- `POST /api/issues` gets optional `processDefinitionId` and `processAssignees` (for `CHOSEN_AT_START` steps).
- `GET /api/issues/{key}/process` returns the flow of the run, with hidden steps reduced to position and state.
- `POST /api/issues/{key}/process/steps/{stepKey}/reopen`, `/reassign`, `/skip`, `/deadline`, and `POST /api/issues/{key}/process/steps` to add a step, plus `POST /api/issues/{key}/process/stop` (initiator only).
- `POST /api/issues/{key}/take` and `/release` (assignee groups, for every issue).

## Web UI

- **Project page → tab Processes:** the definitions, with the number of steps and running runs. **New process**, Edit, Copy, Archive and Delete, disabled with a "?" for users without *Process creator*.
- **Definition editor:**
  - name, description, *Resolve the process automatically when it completes*;
  - the steps, each with:
    - summary and description;
    - who does it: user, group, the initiator, or chosen at start;
    - *waits for*, a multi-select, defaulting to the previous step;
    - *hidden*;
    - duration;
    - test case.
  - a live preview of the flow as a tree.
- **Create issue dialog:** the field Process, and the assignees for steps chosen at start.
- **Issue page:** the Process panel on the process issue, and the *Part of process* link and the waiting or escalation banner on a step.
- **Take / Release** on every issue with an assignee group. *Waiting in my groups* in *My issues*.

## Implementation steps

1. **Assignee groups**, for all issues: column, Take and Release, *My issues*, board, search, mail, API and webhooks. Useful on its own.
2. **Per-issue visibility:** the `ISSUE_VISIBLE` fragment, used by every query that returns issues. Add tests that check every list and endpoint against a hidden issue. This is the security groundwork, done before any step can be hidden.
3. **Migration V30 and definitions:** issue type, permissions, tables, the definitions API with loop detection.
4. **Run engine:** start, activation, done and rejection, escalation, initiator actions, completion and auto-resolve, the deadline job.
5. **Web, definitions:** the Processes tab and the editor with the flow preview.
6. **Web, runs:** the create dialog, the process view, the step banners, the initiator's actions.
7. **Test steps:** the link to the tests module, once that feature exists.
8. **Docs:**
   - user guide: *Processes* and *Assigning to a group*;
   - installation guide: the new permissions and mail templates;
   - `docs/domain-model.md`: the new tables and the per-issue visibility.

## Tests

- **Assignee groups:**
  - Take only by members who may be assigned;
  - Release back to the queue;
  - mail to all members while in the queue, only the assignee after Take;
  - deleting a group clears it on its issues.
- **Visibility:** a hidden step is absent for an outsider from:
  - every list, search result and count;
  - the board, links, the issue picker and sub-task lists;
  - history, comments and attachment downloads;
  - mail and webhooks;
  - the API.

  A direct link gives *not found*. The initiator, the assignee, the group members and `VIEW_PROCESS_STEPS` holders see it.
- **Definitions:** a loop is refused, also through three steps; a definition without a starting step is refused; an archived definition cannot be started.
- **Activation:**
  - a linear process activates one step at a time;
  - two parallel steps activate together;
  - a step that waits for both activates only after the second is done, and exactly once when both finish at the same moment.
- **Waiting:** a waiting step is not in *My issues* or a group queue, and its transitions are refused.
- **Rejection and test failure:**
  - *Won't Fix* escalates the step;
  - a failed test escalates it;
  - in both cases the initiator is mailed once and the red banner is shown.
- **Deadlines:** an overdue step escalates exactly once, also with two instances running the job.
- **Initiator:** only the initiator can reopen, reassign, skip, add, change a deadline or stop. A skipped step lets the next ones activate.
- **Completion:** with auto-resolve the process issue is resolved; without it, it is only completed.
- **Snapshot:** editing a definition does not change running processes.

## Decisions

Taken on 2026-11-20:

1. **Branches:** steps can wait for several steps and run in parallel. The flow is shown as a tree with indents and arrows.
2. **Hidden steps,** per step. Visible only to the initiator, the assignee, the members of the assignee group, and holders of `VIEW_PROCESS_STEPS`. Elsewhere shown as *no access rights*. Every step issue links to the process view.
3. **Assignee group plus Take and Release,** as a general feature for all issues.
4. **Waiting steps** cannot be worked on. A step that waits for nothing may be started right away. This applies only within processes.
5. **Rejection** (and a failed test) is an escalation to the initiator, shown prominently and mailed.
6. **Auto-resolve** is a setting of the process definition (and of the test case, in the tests plan).
7. **Only the initiator** changes a running process.
8. **Process creator** is its own project permission, `MANAGE_PROCESSES`.
9. **Tests and processes stay separate.** A step can be a test run; it is done when that test **passed**.
10. **Deadlines and durations** are optional. An overdue step escalates to the initiator.
11. **Rejection:** every resolution other than *Done* and *Fixed* counts as a rejection.
12. **Deactivated initiator:** the project administrators take over the initiator's role and escalation mails for that run.
13. **Durations** are counted in calendar time.
14. **Planned assignees** of waiting steps are visible to everyone who may see the step.
15. **Test steps** are test runs on a Sub-task; the tests plan allows this as its one exception to "a test run is an issue of type Test".
