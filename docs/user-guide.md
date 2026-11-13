# BuggyWuggy User Guide

BuggyWuggy is an issue tracker: a place where a team records, discusses and follows up bugs,
tasks and other work. If you have used Jira, most of it will feel familiar.

> BuggyWuggy is in early development. This guide describes what works today. Features that
> are not there yet are listed at the end.

## Contents

1. [Concepts](#concepts)
2. [Logging in and out](#logging-in-and-out)
3. [Finding your way around](#finding-your-way-around)
4. [Projects](#projects)
5. [Creating an issue](#creating-an-issue)
6. [The issue page](#the-issue-page)
7. [Working on an issue](#working-on-an-issue)
8. [Sub-tasks and epics](#sub-tasks-and-epics)
9. [Comments](#comments)
10. [Attachments](#attachments)
11. [Linking issues](#linking-issues)
12. [Watching issues](#watching-issues)
13. [Searching for issues](#searching-for-issues)
14. [The board](#the-board)
15. [Saved filters](#saved-filters)
16. [Your profile](#your-profile)
17. [E-mail notifications](#e-mail-notifications)
18. [Project settings](#project-settings)
19. [Permissions: why can't I…?](#permissions-why-cant-i)
20. [Not available yet](#not-available-yet)

## Concepts

| Term | Meaning |
|---|---|
| **Project** | A collection of issues, for example one product or team. Every project has a short **key**, such as `BUG`. |
| **Issue** | One piece of work: a bug, a task, a story… Each issue has a **key** made of the project key and a number, for example `BUG-12`. |
| **Issue type** | The kind of issue: **Epic**, **Story**, **Task**, **Bug** or **Sub-task**. |
| **Status** | Where the issue is in its life: **Open**, **In Progress**, **Reopened**, **Resolved** or **Closed**. |
| **Resolution** | *Why* an issue is done: **Fixed**, **Won't Fix**, **Duplicate**, **Cannot Reproduce** or **Done**. An issue without a resolution is *unresolved*. |
| **Priority** | How important the issue is: **Highest**, **High**, **Medium** (the default), **Low**, **Lowest**. |
| **Assignee** | The person who works on the issue. |
| **Reporter** | The person who reported the issue (normally whoever created it). |
| **Watcher** | Someone who follows an issue. |
| **Labels, components, versions** | Optional ways to group issues. Labels are free words; components (parts of the product) and versions are defined per project by its administrators. |

## Logging in and out

Open BuggyWuggy in your browser and log in with the username and password your administrator
gave you. If you try to open a page while not logged in, you are taken to that page right
after logging in.

To log out, click your name in the top-right corner and choose **Log out**. A login stays
valid for a number of hours of inactivity (8 by default); after that you are asked to log
in again.

After several failed login attempts, logging in is blocked for a while. Wait 15 minutes or
ask your administrator to reset your password.

## Finding your way around

The bar at the top of every page contains:

- **BuggyWuggy** (the logo): back to the start page.
- **Projects**: all projects you can see.
- **Issues**: the search page, opened with the issues assigned to you that are not yet resolved.
- **The search box**: type an issue key such as `BUG-12` and press Enter to open that issue
  straight away. Type anything else to search for it.
- **Administration**: only for BuggyWuggy administrators; see the
  [Installation Guide](installation.md#administration).
- **Your name**: the menu with **Profile** and **Log out**.

Every issue and every search has its own address (URL). You can bookmark it or send it to
a colleague.

## Projects

**Projects** lists the projects you have access to. Use the search field to find a project by
name or key, and click a project to open it. **Board**, on the right of each project, opens its
[board](#the-board).

A project page shows the project's name, key, lead and description, and its issues with the
most recently updated first. From here you can:

- **Create issue**: create a new issue in this project.
- **Board**: open the project's [board](#the-board).
- **Search issues**: open the search page for this project, showing its unresolved issues.
- **Project settings**: only for project administrators; see [Project settings](#project-settings).

### Creating a project

If you are allowed to create projects (by default only administrators), the **Projects** page
has a **Create project** button. Enter:

- **Name**: for example *Customer Portal*.
- **Key**: 2–10 characters; a letter first, then letters, digits or `_`. BuggyWuggy suggests a
  key from the name (*Customer Portal* → `CP`), which you can change. The key becomes part
  of every issue key, so keep it short.
- **Description** (optional).

You become the project lead.

## Creating an issue

Choose **Create issue** on a project page. Fill in:

| Field | |
|---|---|
| **Type** | Required. Task is preselected. |
| **Summary** | Required. One line that says what the issue is about. |
| **Description** | Details: steps to reproduce, expected and actual behaviour, background. |
| **Priority** | Medium unless you choose otherwise. |
| **Assignee** | Only shown if you may assign issues. Leave empty to leave the issue unassigned. |
| **Labels** | Free words without spaces. Press Enter, space or comma after each label. |
| **Components**, **Fix versions** | Only shown when the project has them. |
| **Due date** | Optional. |

Choose **Create**. The new issue opens, with status **Open**. You and the reporter
automatically watch it.

## The issue page

An issue page shows:

- **Breadcrumbs** at the top: project, the parent issue (for sub-tasks and issues in an epic),
  and the issue key.
- The **summary** as the title.
- **Action buttons**: Edit, workflow steps such as *Start Progress*, Watch, and more. You only
  see the buttons for things you are allowed to do.
- **Description**.
- **Sub-tasks** or **Issues in this epic**, if there are any.
- **Attachments**.
- **Linked issues**.
- **External links**, such as the pull requests that referred to an imported issue.
- **Comments** and **History** tabs.
- On the right, the **details**: status and resolution, type, priority, assignee, reporter,
  labels, components, versions, due date, the TaskWarrior *urgency* (click it to see how it is
  calculated, see [TaskWarrior urgency](taskwarrior.md)), and when the issue was created, last updated and
  resolved. For an issue that was imported from another tracker, *Imported from* shows where
  it came from, such as `forgejo/website#12`, with a link to the original.

The **History** tab lists every change ever made to the issue: who changed which field, when,
from what and to what. This includes status changes, links and attachments. History cannot be edited.

Issue keys keep working when a project's key changes: `OLD-12` then takes you to `NEW-12`.

**Imported issues** keep their original authors and dates, and their history from the other
tracker. They get a new number in the project; the original number is shown as *Imported from*
and in the history. People who do not have an account in BuggyWuggy are shown by name, from a
*placeholder* account that cannot log in. Sub-issues from GitHub become sub-tasks (one level
deep) or *relates to* links.

## Working on an issue

### Editing

Choose **Edit** to change the summary, description, priority, labels, components, affects
versions, fix versions and due date. Choose **Save**. Only fields you actually changed are
saved and recorded in the history.

If someone else changed the issue while you were editing, your change is not saved. You see
the message *Changed by someone else*, and the page shows the latest version. Make your change
again. This way nobody overwrites someone else's work by accident.

### Assigning

If you may assign issues, the **Assignee** field on the right is a drop-down. Type part of a
name to search, and choose a person or **Unassigned**. The change is saved immediately. Only
people who may work on issues in the project (by default the *Developers* and *Administrators*
of the project) can be assigned.

### Moving through the workflow

The workflow buttons move the issue to another status:

| Button | From | To |
|---|---|---|
| **Start Progress** | Open, Reopened | In Progress |
| **Stop Progress** | In Progress | Open |
| **Resolve Issue** | Open, In Progress, Reopened | Resolved |
| **Close Issue** | Open, In Progress, Reopened, Resolved | Closed |
| **Reopen Issue** | Resolved, Closed | Reopened |

When you resolve or close an issue, BuggyWuggy asks for the **resolution** (Done is
preselected). When an issue is reopened, its resolution is cleared, so it counts as unresolved
again. Resolved issues are shown with their key struck through in issue lists.

### Deleting

If you may delete issues, the **⋯** menu next to the action buttons has **Delete**. Deleting
cannot be undone:

- Deleting an issue also deletes its sub-tasks.
- Deleting an epic keeps the issues in it; they just no longer belong to an epic.

## Sub-tasks and epics

Issues can be organised in three levels:

```
Epic                  – a large piece of work
 └── Story, Task, Bug – a standard issue
      └── Sub-task    – a small step of a standard issue
```

- On a **standard issue**, choose **Create sub-task** to split the work into steps.
- On an **epic**, choose **Add issue to epic** to create a story, task or bug in it.

The children are listed on the parent's page with their status, and the parent is shown in
the breadcrumbs of each child.

## Comments

The **Comments** tab shows the discussion on an issue, oldest first.

- **Add a comment**: type in the box at the bottom and choose **Add comment**, or press
  **Ctrl+Enter** (**⌘+Enter** on a Mac). You then automatically watch the issue.
- **Edit or delete** your own comment with the **⋯** menu on the comment. Edited comments are
  marked *edited*. Project administrators can edit and delete anyone's comments.

Comments are shown as plain text: line breaks are kept, formatting is not applied yet.

Adding a comment updates the issue's *Updated* time, but never interferes with someone who is
editing the issue at the same time.

## Attachments

Attach files, such as screenshots, logs or documents, in the *Attachments* section of an issue:

- Choose **Attach files** and select one or more files, or
- drag files from your computer and drop them on the *Attachments* section.

Images (PNG, JPEG, GIF and WebP) are shown as thumbnails; click one to see it larger. Other
files are listed with their size, who added them and when; click a name to download it.
For safety, files such as HTML pages or SVG drawings are always downloaded, never opened in
BuggyWuggy itself.

There is a maximum size per file (50 MB unless your administrator changed it); the section
shows the limit while it is empty.

To delete an attachment, choose the bin icon next to it, or in the preview of an image.
Adding and deleting attachments is recorded in the issue's history. Attachments cannot be
added to issues of archived projects.

## Linking issues

Links show how issues relate to each other, also across projects. The available relations are:

| Relation | Seen from the other issue |
|---|---|
| blocks | is blocked by |
| relates to | relates to |
| duplicates | is duplicated by |
| clones | is cloned by |

To add a link, choose **Link issue** in the *Linked issues* section:

1. Choose the relation, as seen from this issue. For example: *this issue* **blocks** …
2. In **Issue**, type the other issue's key or some words from its summary, and pick it from
   the suggestions.
3. Choose **Link**.

The link appears on both issues, phrased from each side (*blocks BUG-2* on one, *is blocked by
BUG-1* on the other), and is recorded in the history of both. Remove a link with the **✕**
next to it.

You only see links to issues you have access to.

## Watching issues

Choose **Watch** on an issue to follow it, and **Stop watching** to stop. The number next to
the button shows how many people watch the issue. If you may manage watchers, hovering over
the button shows their names.

You automatically watch issues you create, issues you are the reporter of, and issues you
comment on.

Watchers get [e-mail notifications](#e-mail-notifications) about changes.

## Searching for issues

Open **Issues** in the top bar. The page shows a list of issues and, above it, the search
conditions. Each issue shows its TaskWarrior *urgency* (see [TaskWarrior urgency](taskwarrior.md)). Every change is applied immediately.

| Condition | |
|---|---|
| **Text** | Words to search for in the summary, description and comments. Every word must occur; words also match as the start of a longer word (`migr` finds *migration*). An issue key such as `BUG-12` finds that issue. |
| **Project, Type, Status, Priority** | Choose one or more values. |
| **Assignee** | **Current user** (whoever uses the search), **Unassigned**, or named people. |
| **Resolution** | **Unresolved** (still open work), **Resolved**, or any. |
| **Labels** | One or more labels; issues with any of them match. |
| **More** | **Reporter**, and date ranges for **Created** and **Updated**. |

Within one condition, the values are alternatives (status *Open* **or** *Reopened*). Different
conditions must all match (status … **and** assignee …).

**Sort by** chooses the order: relevance (when searching for text), updated, created, key,
priority, status, due date or urgency. The button next to it switches between ascending and descending.
**Clear conditions** removes all conditions.

The search conditions are part of the page address, so you can bookmark a search or send its
link to a colleague. They will see the same search, limited to the projects they have access to.

You only ever find issues in projects you may see.

## The board

Every project has a kanban **board**: open it with **Board** on the project list or the project
page. The board shows the project's unresolved issues, and the issues resolved in the last
14 days (the administrator can change that number), in **columns**. Each column shows the issues
with one or more statuses, for example *To Do* (Open, Reopened), *In Progress* and *Done*
(Resolved, Closed). The number next to a column's name is how many issues it holds; hover over
the name to see its statuses.

A card shows the issue's type, key, summary, priority, assignee, epic and
[TaskWarrior urgency](taskwarrior.md). Click a card to open the issue.

**Swimlanes** divide the board into horizontal lanes: **None**, or one lane per **Assignee**,
**Epic** or **Priority**. The administrator sets which one a board starts with; the choice you
make above the board is remembered in your browser, per project. Sub-tasks are in the lane of
their parent's epic, and an epic is in its own lane.

**Drag a card** to change it:

- **To another column**: the issue goes through the workflow transition to a status of that
  column, as with the transition buttons on the issue page. When several transitions lead to the
  column, or the column means *done*, BuggyWuggy asks which transition and which resolution to
  use. When no transition leads there from the issue's status, the card goes back.
- **To another lane**: the field of the lane changes: the assignee (to *Unassigned* removes
  it), the epic (to *No epic* removes it), or the priority. Only standard issues can move to
  another epic; a sub-task follows its parent.
- **Up or down**: the order of the issues on the board. The order is the same in every lane
  and column: an issue you move to the top of *To Do* stays above the others when it moves on.

One drag can do all three at once. Every change appears in the issue's **History**, like the
same change made on the issue page; a new place in the order shows as *Rank: Ranked higher* or
*Ranked lower*. Nobody is notified by e-mail of a new order.

You can also move a card with the keyboard: Tab to it, press Space, move it with the arrow keys
and press Space again (Escape cancels).

You need the same permissions as for the same change on the issue page: *transition* for
another column, *assign* for another assignee, and *edit* for another epic, priority or place.
Without any of them the board is read-only. Issues with a status that is in no column are not
shown; the board then says which statuses those are.

Project administrators set the board's columns under
[Project settings → Board](#project-settings).

## Saved filters

A **filter** is a saved search.

- **Save a search**: set up the conditions, choose **Save filter**, enter a name, choose
  who may use it, and choose **Save**:
  - **Only me**
  - **All logged-in users**
  - **Members of a project**: everyone who can see the chosen project.
- **Use a filter**: open the **Filters** menu. It lists *My filters* and the filters others
  shared with you.
- **Change a filter**: open it and change the conditions. The title shows *Modified*.
  - **Save changes** stores the new conditions in your filter.
  - **Discard changes** restores the saved conditions.
  - **Save as…** stores the conditions as a new filter. This also works for filters someone
    else shared with you.
- **Rename, share or delete** your own filter with the **⋯** menu next to its title.

A filter with **Assignee: Current user** shows each person their own issues. One shared filter
such as *My open issues* therefore works for everybody.

## Your profile

Choose **Profile** in the menu under your name to change:

- your **full name** and **e-mail address**;
- your **language**: the language of the e-mails you get (the web interface itself is in
  English for now);
- your **time zone** (stored for later use; the web interface shows times in the time zone
  of your browser);
- your **e-mail notifications**: see below;
- your **password**: enter the current one and the new one (at least 12 characters) twice.
  You stay logged in in this browser; your other sessions are logged out.

Your username cannot be changed.

## E-mail notifications

BuggyWuggy mails you when something happens to an issue that you **watch**, **reported** or are
**assigned** to: when it is created, changed, assigned, moves to another status, gets a comment
or an attachment, or is deleted. When an issue is assigned to someone else, the previous
assignee gets a mail too.

- **One mail per burst of changes.** When someone makes several changes to an issue in a short
  time, for example edits it and then comments, you get one mail with all of them, about a
  minute after the last change.
- **Not about your own changes**, unless you turn on *Also about my own changes* in your
  profile.
- **Only what you may see.** If you lose access to a project, you no longer get mails about
  its issues.
- **Turning mails off:** in your [profile](#your-profile), turn off *Mail me about issues*.
  Every mail has a link to your profile. To stop mails about one issue only, choose **Stop
  watching** on that issue.

Mails are in the language of your profile. Administrators can change the text of the mails;
see the [Installation Guide](installation.md#mail-templates).

## Project settings

Project administrators (by default the project's *Administrators* role) see a **Project
settings** button on the project page. The settings have five tabs.

**Details**: name, description, project lead and **default assignee** (who new issues are
assigned to when the reporter chooses nobody: nobody, or the project lead). BuggyWuggy
administrators can also change the project **key** here (old issue keys keep working), and
**archive**, **restore** or **delete** the project:

- An archived project is hidden from the project list (administrators can show it with
  *Show archived projects*) and is read-only: its issues can be viewed, but not changed or
  commented on.
- Deleting a project deletes all its issues, comments, attachments and history. To confirm,
  you must type the project key.

**Components**: parts of the product, such as *Backend* or *Documentation*. A component can
have a lead and its own default assignee: the project default, the component lead, the project
lead, or nobody. Deleting a component removes it from all issues.

**Versions**: releases of the product. Use the arrows to change their order; that order is
used wherever versions are offered. With the **⋯** menu you can edit a version, mark it as
released, archive it, or delete it (which removes it from all issues).

**People and roles**: who has which role in this project. Add groups (for example `users`)
or individual people to *Users*, *Developers* or *Administrators*. What each role may do is
described under [Permissions](#permissions-why-cant-i). Changes apply immediately.

**Board**: the columns of the project's [board](#the-board). A project starts with the
default board of the BuggyWuggy administrator. Give each column a name and the statuses it
shows, and use the arrows to change their order; a status can be in only one column, and
statuses that are in no column are listed below the columns, as their issues are not shown.
Also choose the swimlanes the board starts with and how many days resolved issues stay on it
(0: they leave the board at once). **Save** gives the project these columns as its own: changes
to the default board then no longer apply to it. **Use the default board** undoes that.

## Permissions: why can't I…?

What you can do depends on your role in each project. Buttons for things you may not do are
not shown. With the default settings:

| You want to… | Who can |
|---|---|
| See a project and its issues, create issues, comment, attach files, delete your own attachments | Everyone in the project's *Users* role (by default all users) |
| Edit, assign, transition and link issues; be assigned issues; manage watchers | *Developers* and *Administrators* of the project |
| Delete issues; edit or delete other people's comments and attachments; change the project settings | *Administrators* of the project |
| Create projects; archive, delete or re-key projects; manage users and groups | BuggyWuggy administrators |

If you need to do more in a project, ask your BuggyWuggy administrator to add you to the
project's *Developers* role.

A project or issue you have no access to looks exactly like one that does not exist
(*Not found*). This is deliberate: it does not reveal what exists.

## Not available yet

These features are planned but not available in the current version:

- Formatted text (Markdown) in descriptions and comments
- Scrum boards with sprints and a backlog, dashboards and reports
- A text query language like Jira's JQL
- The web interface in other languages than English (e-mails are available in English and Dutch)
