# Tests: test cases and test runs

Status: proposal · Last updated: 2026-11-20 · See also: [domain-model.md](domain-model.md)

Reusable test cases with steps, run as issues of type Test.

## Goal

- Users with the permission **Test creator** write tests (*test cases*) for a project. A test case is a list of steps; each step has an action and, optionally, an expected result.
- Any user who may create issues in the project can run a test. They create an issue, choose the type **Test** and then the test case. The issue gets the steps as a checklist.
- Each step gets a result: *passed*, *failed* or *not run* (the start). A failed step can have a note. When no step is *not run* any more, the test is complete: *passed* when all steps passed, otherwise *failed*.

## Concepts

| Name | What it is |
|---|---|
| **Test case** | The definition: name, description and an ordered list of steps (action + expected result). Belongs to one project. Managed by test creators. |
| **Test run** | An issue of type *Test*, created from a test case. It has its own copy of the steps, each with a result. The one exception: a step of a process (a *Sub-task*, see [plan-processes.md](plan-processes.md)) can also carry a test run. |

A test run copies the steps when it is created. Editing a test case later never changes runs that already exist, so a finished run always shows what was actually tested.

## Data model (migration V29)

- **Issue type `Test`:** icon `test`, hierarchy level 0, after *Feature*. It is added like `V19__feature_issue_type.sql`: only when no type named "test" exists.
- **Project permission `MANAGE_TESTS`** (shown as *Test creator*):
  - added to `ProjectPermission` and to the check constraint `permission_grant_permission_ck`;
  - granted by default to the project role *Administrators* in every permission scheme.
- **`test_case`:** `id`, `project_id` (FK project, on delete cascade), `name`, `description` (Markdown), `auto_resolve boolean default false`, `archived boolean`, `created_by`, `created_at`, `updated_at`. Unique `(project_id, lower(name))`.
- **`test_case_step`:** `id`, `test_case_id` (FK, on delete cascade), `position`, `action` (Markdown, required), `expected_result` (Markdown, optional). Unique `(test_case_id, position)`.
- **`issue_test_run`:** `issue_id` (PK, FK issue, on delete cascade), `test_case_id` (FK test_case, on delete set null), `test_case_name` (a copy, so the run still says which test it was), `auto_resolve` (copied from the test case), `outcome` (`PASSED` or `FAILED`; null while not complete), `completed_at` (null while not complete).
- **`issue_test_step`:** `id`, `issue_id` (FK issue_test_run, on delete cascade), `position`, `action`, `expected_result`, `result` (text, default `NOT_RUN`), `note` (Markdown, optional), `result_by` (FK user_account, nullable), `result_at`.

**Step results are open for extension.** `result` is text with a check constraint (`NOT_RUN`, `PASSED`, `FAILED`), mapped to a Java enum, like `status.category`. Each value says whether it is *final* (counts as run) and whether it makes the run fail. A later state such as `BLOCKED` (not final) or `SKIPPED` (final, does not fail the run) is then one value in the enum plus a migration that widens the check constraint. The completion rule and the UI work from those two properties, not from a fixed list.

## Rules

**Test cases**
- Everyone who may browse the project can see its test cases.
- Only holders of `MANAGE_TESTS` can create, edit, reorder steps, copy, archive or delete them.
- A test case with runs cannot be deleted, only archived. The Delete button is disabled with a "?" that says how many runs use it.
- Archived test cases can no longer be chosen for a new run, but existing runs keep working.
- A test case needs at least one step, and every step needs an action. The expected result is optional.

**Creating a run**
- In the create dialog, choosing the type *Test* adds a required field **Test**. It lists the project's test cases that are not archived.
- The summary is prefilled as `Test: <name>` and can be changed.
- The server copies the steps into `issue_test_step` in the same transaction as the issue, all with result `NOT_RUN`.
- `testCaseId` is required for type Test and refused for every other type. The one exception is a process step: the processes feature creates a test run on its Sub-task through the tests module's service, not through this field.
- When the project has no test cases, the type *Test* stays in the list but is disabled, with a "?" that explains that a test creator must first make a test case.

**Recording results**
- Allowed for the assignee, the reporter, and holders of `EDIT_ISSUE`. For others, the result buttons are disabled with a "?".
- Setting a result stores `result_by` and `result_at`. Setting it back to *not run* clears them and the note. Steps can be done in any order.
- A note is optional; the UI asks for one when a step fails, but does not require it.
- An archived project is read-only, as for everything else.

**Complete**
- The run is complete when no step has a non-final result (now: no step is *not run*). Then `completed_at` is set and `outcome` becomes `FAILED` when any step failed, otherwise `PASSED`.
- Changing a result of a complete run recomputes this. Back to *not run* makes the run incomplete again (`outcome` and `completed_at` cleared); passed ↔ failed changes the outcome.
- By default, completion does **not** change the issue's status: resolving or closing stays a normal workflow step for the user.
- **Auto-resolve:** a test case can have *Resolve the run automatically when it passes* switched on. The run copies this setting when it is created. When such a run completes with outcome `PASSED`, and the user who set the last result may transition the issue, the issue goes through a transition from its current status to a Done status, with resolution *Done* (in the Default workflow: *Resolve Issue*). A run that completes as `FAILED` is never resolved automatically: someone has to look at it. When there is no such transition, or the user may not transition, the run is only marked complete.
- The completion check runs in the same transaction as the result change, with a row lock on the run. Two people finishing the last two steps at the same moment then still get one consistent outcome.

**Other actions**
- The issue type of a run cannot be changed, and no other issue can be changed to *Test*. The type field is disabled with a "?".
- Moving a run to another project keeps its steps and results. `test_case_id` is set to null, because the test case belongs to the old project, and `test_case_name` keeps the name.

## History, notifications, webhooks

- Every result change is a history entry on the issue: *Test step 3 "Log in as admin": not run → failed*, with the note.
- Completion is recorded as its own entry: *Test run complete: failed (1 of 7 steps failed)*, or *passed*.
- Result changes are ordinary issue changes: the existing quiet period combines them into one *issue updated* mail to the watchers, not one mail per step.
- Webhooks: `issue.updated` with the step change and, on completion, the outcome.

## REST API

All endpoints follow the existing conventions (project key in the path, ProblemDetails errors) and are added to `web/src/api/schema.d.ts`.

- `GET    /api/projects/{key}/test-cases` lists the test cases. With `?archived=true`, the archived ones are included.
- `POST   /api/projects/{key}/test-cases` creates one (`MANAGE_TESTS`).
- `GET    /api/projects/{key}/test-cases/{id}` returns one, with its steps.
- `PUT    /api/projects/{key}/test-cases/{id}` saves the name, description and all steps at once (`MANAGE_TESTS`).
- `POST   /api/projects/{key}/test-cases/{id}/archive` and `/unarchive` (`MANAGE_TESTS`).
- `DELETE /api/projects/{key}/test-cases/{id}` deletes one, only when it has no runs (`MANAGE_TESTS`).
- `POST   /api/issues` gets an optional `testCaseId`.
- `GET    /api/issues/{key}/test-steps` returns the steps with result, note, by whom and when, plus `outcome` and `completedAt`.
- `PUT    /api/issues/{key}/test-steps/{stepId}` with `{ "result": "FAILED", "note": "…" }` sets the result of a step.
- The list of possible results, with their *final* and *fails the run* properties, is part of the lookups, so the UI and API clients pick up new states without a change.

## Web UI

- **Project page → tab Tests:**
  - visible to everyone who may browse the project;
  - a list of test cases with the number of steps and runs, and the outcome and date of the last run;
  - **New test case**, Edit, Copy, Archive and Delete, disabled with a "?" for users without *Test creator*.
- **Test case editor:**
  - name and description (Markdown editor);
  - a checkbox *Resolve the run automatically when it passes*;
  - steps as a list with arrows to reorder them, like versions in project settings;
  - per step an *Action* field and an optional *Expected result* field, both Markdown.
- **Create issue dialog** (`CreateIssueModal.tsx`): the extra field Test when the type is Test.
- **Issue page, a panel Test steps:**
  - the test case name (a link, as long as the test case exists) and the outcome when complete;
  - a progress bar "5 / 7 run, 1 failed";
  - per step: the action, the expected result, buttons *Passed* / *Failed* / *Not run*, and a note field that opens when a step fails;
  - who set each result and when.
- **Issue lists and the board:** for runs, a small indicator "5/7", red when a step failed, with a check mark or a cross when complete.
- **Search:** filters *Test case* and *Test outcome* (not complete / passed / failed).

## Implementation steps

1. **Migration and model:** V29 with the issue type, the permission, the tables and the default grants. Add `ProjectPermission.MANAGE_TESTS` and the enum for step results.
2. **Backend for test cases:** a package `org.buggywuggy.testcase` with service, controller, DTOs and permission checks.
3. **Backend for runs:** creating a run in `IssueService`, the steps endpoints, the completion rule and outcome, history, and the type-change and move rules in `IssueService` and `IssueMover`.
4. **Web, test cases:** the Tests tab and the test case editor.
5. **Web, runs:** the create dialog, the Test steps panel, the indicator in lists and on the board, and the search filters.
6. **Docs:**
   - user guide: a new section *Tests*;
   - installation guide: the permission *Test creator* in the table *Default groups, roles and permissions*;
   - `docs/domain-model.md`: the new tables.

## Tests

- **Permissions:** without `MANAGE_TESTS`, creating or editing a test case returns 403. Setting results is refused for users who are neither assignee, reporter nor holder of `EDIT_ISSUE`.
- **Snapshot:** editing a test case after a run was created leaves the run's steps unchanged.
- **Completion:**
  - when the last *not run* step gets a result, `completedAt` is set; the outcome is `PASSED` when all passed and `FAILED` when one failed;
  - setting a step back to *not run* makes the run incomplete again;
  - changing a step from failed to passed changes the outcome;
  - without auto-resolve, the issue's status never changes because of results;
  - with auto-resolve, a passed run is resolved with *Done*, a failed run is not, and a user without `TRANSITION_ISSUE` only completes the run;
  - switching auto-resolve on a test case does not change runs that already exist.
- **Concurrency:** two transactions finish the last two steps at the same moment; the run ends up complete with the right outcome, and completion is recorded once.
- **Extension:** a test-only result state that is not final keeps a run incomplete, without changes to the completion code.
- **Rules:**
  - `testCaseId` with another type returns 400;
  - type Test without `testCaseId` returns 400;
  - an archived test case cannot be chosen;
  - deleting a test case with runs returns 409;
  - changing the type of a run is refused;
  - a move keeps the steps and results.
- **Migration:** on an existing database, the issue type, the permission and the grants are added once, and running the migration again adds nothing (the issue type is guarded by `not exists`).

## Decisions

Taken on 2026-11-20:

1. **Test creator is a project permission** (`MANAGE_TESTS`), granted through permission schemes, with test cases per project. Not a global permission with a shared library.
2. **Results can be set by the assignee, the reporter and `EDIT_ISSUE` holders.**
3. **A complete run is marked complete** (with its outcome). It only resolves the issue when its test case has *auto-resolve* switched on and the run passed; otherwise resolving stays a workflow step for the user.
4. **Steps are passed / failed / not run**, with an optional note, stored so that more states can be added later.
5. **A step has an action and an optional expected result.**

## Later

- More step results, such as *blocked* or *skipped* (see *Step results are open for extension*).
- **Report bug** on a failed step: a new Bug issue with the step, its expected result and the note prefilled, linked to the run.
- Running a test case again as a copy of an earlier run, and a history of outcomes per test case.
