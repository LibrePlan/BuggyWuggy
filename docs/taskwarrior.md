# TaskWarrior urgency

BuggyWuggy can rank issues the way [TaskWarrior](https://taskwarrior.org) ranks tasks: each
open issue gets an **urgency**, a number that says how much it asks for attention, and the
issue list can put the most urgent issues first. The urgency is calculated as TaskWarrior calculates it
([TaskWarrior: urgency](https://taskwarrior.org/docs/urgency/)), with the qualities of an issue
in BuggyWuggy in place of those of a task.

## Contents

1. [Where the urgency is shown](#where-the-urgency-is-shown)
2. [How the urgency is calculated](#how-the-urgency-is-calculated)
3. [The terms](#the-terms)
4. [Configuration](#configuration)
5. [API](#api)
6. [Differences from TaskWarrior](#differences-from-taskwarrior)

## Where the urgency is shown

The issue list (**Issues** in the top bar) has an **Urgency** column. To put the most urgent
issues first, choose **Urgency** under **Sort by** (descending). Like every search condition,
the sort is part of the page address and of a saved filter, so a filter such as *my unresolved
issues, by urgency* can be saved and shared.

On the **issue page**, the details show the issue's **Urgency**. Click the value to see how it
was calculated, line by line, as TaskWarrior's `task info` shows it:

```
Urgency       2.9

    tags    0.9 *    1 =    0.9
    age       1 *    2 =      2
                         ------
                            2.9
```

Each line is one term: the issue's value for that term (its *factor*), times the term's
*coefficient*, is the term's share. Only terms that add something are listed. The urgency is
the sum of the shares.

**Resolved issues have no urgency** (shown as `–`): TaskWarrior's `next` report lists only
pending tasks. When sorting by urgency, they come last.

## How the urgency is calculated

The urgency is a sum of terms:

    urgency = Σ factor(term) × coefficient(term)

Most factors are 0 or 1 (the issue has the quality or not); the factors for tags, annotations,
due date and age vary between 0 and 1. The coefficients come from the configuration file, with
TaskWarrior's defaults. A positive coefficient makes issues with that quality more urgent, a
negative one less urgent.

The urgency is calculated when it is shown, so it changes by itself as time passes (age, due
date) and as soon as an issue changes.

## The terms

| Term | Factor in BuggyWuggy | Default coefficient |
|---|---|---|
| `project` | 1 when the issue has at least one component | 1.0 |
| `active` | 1 when its status is in the category *In progress* | 4.0 |
| `scheduled` | 1 when it has the label `scheduled` | 5.0 |
| `waiting` | 1 when it has the label `waiting` | −3.0 |
| `blocked` | 1 when an unresolved issue *blocks* it | −5.0 |
| `annotations` | its comments: 0.8 for one, 0.9 for two, 1.0 for three or more | 1.0 |
| `tags` | its labels: 0.8 for one, 0.9 for two, 1.0 for three or more | 1.0 |
| `due` | its due date (see below) | 12.0 |
| `age` | its age in days divided by `age-max` (365), at most 1.0 | 2.0 |
| `blocking` | 1 when it *blocks* an unresolved issue | 8.0 |
| `priority <name>` | 1, for the issue's priority | Highest 8.0, High 6.0, Medium 3.9, Low 1.8, Lowest 0.9 |
| `project <KEY>` | 1, for the issue's project, when that project has a coefficient | none |
| `tag <label>` | 1, for each label that has a coefficient | `next` 15.0 |

**Due date.** Like TaskWarrior, a range of 21 days is mapped to 0.2–1.0. With *days overdue* =
now − the due date (counted from the start of that day, negative before it):

| Days overdue | Factor |
|---|---|
| 7 or more (a week or more overdue) | 1.0 |
| from −14 to 7 | (days overdue + 14) × 0.8 / 21 + 0.2 |
| less than −14 (more than two weeks ahead) | 0.2 |
| no due date | 0 |

So an issue due today counts for 0.73 × 12 = 8.8, one due in two weeks or later for
0.2 × 12 = 2.4.

**Blocks.** *Blocked* and *blocking* use links of the type **Blocks** (see *Linking issues*
in the [User Guide](user-guide.md#linking-issues)) to issues that are not resolved. Once the
other issue is resolved, the link no longer counts.

**The `next` label.** As in TaskWarrior, the label `next` adds 15.0: an issue with it is
practically always at the top. Its label also counts for `tags`.

**Examples.**

- A year-old issue with two labels: tags 0.9 × 1 + age 1 × 2 = **2.9**.
- An issue with priority *High*, the label `next`, that blocks another and was created 73 days
  ago: tags 0.8 + age 0.4 + blocking 8 + priority High 6 + tag next 15 = **30.2**.
- An issue blocked by an unresolved one, without priority, created today: blocked −5 = **−5**.

## Configuration

The coefficients are set in the configuration file, under `buggywuggy.taskwarrior.urgency`
(see *Configuration file* in the [Installation Guide](installation.md#configuration-file)).
The template BuggyWuggy writes contains them all, commented out, with their defaults. A file
that existed before this version does not have them yet; copy the section from the template
(`java -jar buggywuggy.jar config-template`). Restart BuggyWuggy after a change.

```yaml
buggywuggy:
  taskwarrior:
    urgency:
      due-coefficient: 12.0          # urgency.due.coefficient
      blocking-coefficient: 8.0      # urgency.blocking.coefficient
      scheduled-coefficient: 5.0     # urgency.scheduled.coefficient
      active-coefficient: 4.0        # urgency.active.coefficient
      age-coefficient: 2.0           # urgency.age.coefficient
      age-max: 365                   # urgency.age.max (days; 0 means the age always counts fully)
      annotations-coefficient: 1.0   # urgency.annotations.coefficient
      tags-coefficient: 1.0          # urgency.tags.coefficient
      project-coefficient: 1.0       # urgency.project.coefficient
      waiting-coefficient: -3.0      # urgency.waiting.coefficient
      blocked-coefficient: -5.0      # urgency.blocked.coefficient
      priority:                      # urgency.uda.priority.<name>.coefficient
        Highest: 8.0
        High: 6.0
        Medium: 3.9
        Low: 1.8
        Lowest: 0.9
      tags:                          # urgency.user.tag.<label>.coefficient
        next: 15.0
      projects: {}                   # urgency.user.project.<KEY>.coefficient, e.g. {SHOP: 5.0}
```

- **The maps** `priority`, `tags` and `projects` add to their defaults: entries you set
  replace the default for that key, the others keep theirs. So `priority: {High: 7.0}` changes
  only *High*. Set a value to 0 to switch it off. Names are matched without regard to case.
- **Priorities** that are not listed (for example one an administrator added) count 0.
- **Environment variables** work too, for example
  `BUGGYWUGGY_TASKWARRIOR_URGENCY_DUE_COEFFICIENT=10.0`. For the maps the file is easier.

TaskWarrior's advice holds here as well: use the urgency for a while before changing anything,
change coefficients in small steps (±1.0), and keep any single term from dominating the others.
Setting the priority coefficients to 1.0 and all others to 0 sorts by priority alone.

## API

- `POST /api/v1/issues/search` returns `urgency` with every issue (null for resolved issues),
  and sorts by it with `"sort": "URGENCY"` (`"direction": "DESC"`, the default, puts the most
  urgent first; resolved issues always come last). Sorting by urgency considers at most the
  10,000 most recently created issues that match.
- `GET /api/v1/issues/{key}/urgency` returns the urgency with its terms:

```json
{
  "key": "SHOP-12",
  "urgency": 2.9,
  "resolved": false,
  "terms": [
    { "name": "tags", "factor": 0.9, "coefficient": 1.0, "urgency": 0.9 },
    { "name": "age", "factor": 1.0, "coefficient": 2.0, "urgency": 2.0 }
  ]
}
```

Both only show issues of projects you may browse.

## Differences from TaskWarrior

BuggyWuggy has no fields for some of TaskWarrior's qualities; these are used instead:

| TaskWarrior | BuggyWuggy |
|---|---|
| tags | labels |
| annotations | comments |
| project (the task has one) | the issue has a component; every issue is in a project |
| `urgency.user.project.<name>` | per project key |
| priority H, M, L | the priorities by name, with Highest and Lowest added |
| started (active) | status category *In progress* |
| scheduled (a scheduled date in the past) | the label `scheduled` |
| waiting (a wait date in the future) | the label `waiting` |
| depends | links of the type *Blocks* to unresolved issues |
| completed and deleted tasks | resolved issues, without urgency |

Not supported: `urgency.inherit`, `urgency.user.keyword.*`, and coefficients for other
user-defined attributes.
