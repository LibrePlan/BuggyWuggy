/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.forbidden;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.issue.IssueDtos.IssueRef;
import org.buggywuggy.issue.IssueDtos.IssueResponse;
import org.buggywuggy.issue.IssueDtos.MoveBlocker;
import org.buggywuggy.issue.IssueDtos.MoveIssueRequest;
import org.buggywuggy.issue.IssueDtos.MoveOptions;
import org.buggywuggy.issue.IssueDtos.MovePreview;
import org.buggywuggy.issue.IssueDtos.ProjectRef;
import org.buggywuggy.issue.IssueFieldStore.Named;
import org.buggywuggy.issue.IssueFieldStore.VersionKind;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;
import org.buggywuggy.issue.IssueService.IssueAccess;
import org.buggywuggy.project.Project;
import org.buggywuggy.project.ProjectAccess;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.project.ProjectPermissionService;
import org.buggywuggy.project.ProjectService;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserAccount;
import org.buggywuggy.user.UserAccountRepository;
import org.buggywuggy.user.UserDtos.UserRef;
import org.buggywuggy.workflow.WorkflowService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves an issue to another project. The issue itself moves: it keeps its id, and with it its
 * comments, attachments, history, watchers and links, and gets the next number of the new
 * project. In its old place comes a stub: a closed issue with the old key and summary,
 * resolution Moved, a comment "Moved to NEW-12" and a Moved link to the issue.
 *
 * <p>Only standard issues without sub-tasks can be moved (not epics, not sub-tasks), because
 * parent and child must be in the same project. An issue in an epic leaves the epic. Moving
 * takes the right to delete issues in the old project and to create issues in the new one.
 */
@Service
@Transactional
public class IssueMover {

    private final IssueService issues;
    private final IssueRepository issueRepository;
    private final IssueFieldStore fields;
    private final IssueHistory history;
    private final ProjectService projects;
    private final ProjectPermissionService permissions;
    private final WorkflowService workflows;
    private final StatusRepository statuses;
    private final ResolutionRepository resolutions;
    private final UserAccountRepository users;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public IssueMover(IssueService issues, IssueRepository issueRepository, IssueFieldStore fields,
            IssueHistory history, ProjectService projects, ProjectPermissionService permissions,
            WorkflowService workflows, StatusRepository statuses, ResolutionRepository resolutions,
            UserAccountRepository users, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.issues = issues;
        this.issueRepository = issueRepository;
        this.fields = fields;
        this.history = history;
        this.projects = projects;
        this.permissions = permissions;
        this.workflows = workflows;
        this.statuses = statuses;
        this.resolutions = resolutions;
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** What the issue page needs for the Move button: whether it can be used, and the projects. */
    @Transactional(readOnly = true)
    public MoveOptions options(BuggyWuggyPrincipal user, String idOrKey) {
        IssueAccess access = issues.access(user, idOrKey);
        MoveBlocker blocker = blocker(access);
        List<ProjectRef> targets = blocker == null ? targets(user, access.issue().getProjectId()) : List.of();
        if (blocker == null && targets.isEmpty()) {
            blocker = MoveBlocker.NO_TARGETS;
        }
        return new MoveOptions(blocker == null, blocker, targets);
    }

    /** What would change by moving the issue to {@code project}, for the dialog to show. */
    @Transactional(readOnly = true)
    public MovePreview preview(BuggyWuggyPrincipal user, String idOrKey, String project) {
        IssueAccess access = issues.access(user, idOrKey);
        requireMovable(access);
        return plan(access, target(user, access, project)).preview();
    }

    public IssueResponse move(BuggyWuggyPrincipal user, String idOrKey, MoveIssueRequest request) {
        IssueAccess access = issues.access(user, idOrKey);
        Issue issue = access.issue();
        if (request.version() != null && request.version() != issue.getVersion()) {
            throw conflict("error.conflict");
        }
        requireMovable(access);
        ProjectAccess target = target(user, access, request.project());
        Plan plan = plan(access, target);

        Project source = access.project().project();
        Project destination = target.project();
        long oldNumber = issue.getNumber();
        String oldKey = access.key();
        long number = issues.nextNumber(destination.getId());
        String newKey = destination.getProjectKey() + "-" + number;
        Instant now = clock.instant();
        UserAccount mover = users.getReferenceById(user.id());

        // The issue itself, with one history entry that tells its watchers.
        Changes changes = new Changes();
        changes.record(Fields.PROJECT, source.getId(), source.getName(), destination.getId(), destination.getName());
        changes.record(Fields.KEY, null, oldKey, null, newKey);
        if (issue.getParent() != null) {
            changes.record(Fields.PARENT, issue.getParent().getId(), source.getProjectKey() + "-"
                    + issue.getParent().getNumber(), null, null);
            issue.setParent(null);
        }
        if (plan.removedAssignee() != null) {
            IssueService.recordUser(changes, Fields.ASSIGNEE, issue.getAssignee(), null);
            issue.setAssignee(null);
        }
        plan.components().dropped().forEach(n -> changes.record(Fields.COMPONENT, n.id(), n.name(), null, null));
        plan.affects().dropped().forEach(n -> changes.record(Fields.AFFECTS_VERSION, n.id(), n.name(), null, null));
        plan.fixes().dropped().forEach(n -> changes.record(Fields.FIX_VERSION, n.id(), n.name(), null, null));
        changes.record(Fields.LINK, null, null, oldKey, "moved from " + oldKey);
        issue.moveTo(destination.getId(), number);
        issue.setUpdatedAt(now);
        // Frees the old number for the stub, and fails on a concurrent change (optimistic lock).
        issueRepository.flush();
        long id = issue.getId();
        jdbc.update("update issue set board_rank = null where id = :id", Map.of("id", id));
        fields.replaceComponents(id, plan.components().kept());
        fields.replaceVersions(id, VersionKind.AFFECTS, plan.affects().kept());
        fields.replaceVersions(id, VersionKind.FIX, plan.fixes().kept());

        // The stub in the old place, closed as Moved. Written without notifications: nobody
        // watches it, and the issue's own watchers are told about the move above.
        long workflowId = workflows.workflowFor(source.getWorkflowSchemeId(), issue.getIssueType().getId());
        Issue stub = new Issue(source.getId(), oldNumber, issue.getIssueType(),
                statuses.getReferenceById(doneStatus(workflowId)), issue.getSummary(), mover, now);
        stub.setReporter(mover);
        stub.setPriority(issue.getPriority());
        stub.setResolution(resolutions.getReferenceById(movedResolution()));
        stub.setResolvedAt(now);
        issueRepository.saveAndFlush(stub);
        long stubId = stub.getId();
        jdbc.update("""
                insert into comment (issue_id, author_id, body, created_at, updated_at)
                values (:issue, :author, :body, :now, :now)""",
                new MapSqlParameterSource().addValue("issue", stubId).addValue("author", user.id())
                        .addValue("body", "Moved to " + newKey + ".").addValue("now", Timestamp.from(now)));
        jdbc.update("""
                insert into issue_link (link_type_id, source_issue_id, target_issue_id, created_at)
                select id, :stub, :issue, :now from issue_link_type where lower(name) = 'moved'""",
                new MapSqlParameterSource().addValue("stub", stubId).addValue("issue", id)
                        .addValue("now", Timestamp.from(now)));
        Changes onStub = new Changes();
        onStub.record(Fields.LINK, null, null, newKey, "moved to " + newKey);
        history.record(stubId, user.id(), now, onStub);

        history.save(id, user.id(), now, changes);
        issueRepository.flush();
        return issues.get(user, newKey);
    }

    // --- rules -------------------------------------------------------------------------

    /** Why the issue cannot be moved at all, or null. The order is the order of importance. */
    private MoveBlocker blocker(IssueAccess access) {
        Issue issue = access.issue();
        if (access.project().project().isArchived()) {
            return MoveBlocker.ARCHIVED;
        }
        if (!access.project().has(ProjectPermission.DELETE_ISSUE)) {
            return MoveBlocker.PERMISSION;
        }
        int level = issue.getIssueType().getHierarchyLevel();
        if (level == IssueType.EPIC) {
            return MoveBlocker.EPIC;
        }
        if (issue.getIssueType().isSubtask()) {
            return MoveBlocker.SUBTASK;
        }
        if (issueRepository.hasChildren(issue.getId())) {
            return MoveBlocker.HAS_SUBTASKS;
        }
        return null;
    }

    private void requireMovable(IssueAccess access) {
        MoveBlocker blocker = blocker(access);
        if (blocker == null) {
            return;
        }
        throw switch (blocker) {
            case ARCHIVED -> conflict("project.archived", access.project().project().getProjectKey());
            case PERMISSION -> forbidden("issue.move-permission");
            case EPIC -> badRequest("issue.move-epic");
            case SUBTASK -> badRequest("issue.move-subtask");
            case HAS_SUBTASKS -> badRequest("issue.move-has-subtasks");
            case NO_TARGETS -> new IllegalStateException(blocker.name());
        };
    }

    /** The project to move to: another one, not archived, where the user may create issues. */
    private ProjectAccess target(BuggyWuggyPrincipal user, IssueAccess access, String project) {
        ProjectAccess target;
        try {
            target = projects.access(user, project.strip());
        } catch (ApiException e) {
            throw badRequest("issue.move-target-invalid", project);
        }
        if (target.projectId() == access.issue().getProjectId()) {
            throw badRequest("issue.move-same-project");
        }
        if (target.project().isArchived()) {
            throw conflict("project.archived", target.project().getProjectKey());
        }
        if (!target.has(ProjectPermission.CREATE_ISSUE) || !target.has(ProjectPermission.BROWSE_PROJECT)) {
            throw forbidden("issue.move-target-permission", target.project().getProjectKey());
        }
        long workflowId = workflows.workflowFor(target.project().getWorkflowSchemeId(),
                access.issue().getIssueType().getId());
        if (!workflows.containsStatus(workflowId, access.issue().getStatus().getId())) {
            throw badRequest("issue.move-workflow", access.issue().getStatus().getName(),
                    target.project().getProjectKey());
        }
        return target;
    }

    /** Other projects, not archived, where the user may create issues; by key. */
    private List<ProjectRef> targets(BuggyWuggyPrincipal user, long sourceProjectId) {
        return jdbc.query("""
                select p.id, p.key, p.name from project p
                where not p.archived and p.id <> :source and """ + " " + ProjectPermissionService.PROJECT_BROWSABLE + """
                 order by p.key""", Map.of("source", sourceProjectId, "userId", user.id()),
                (rs, n) -> new ProjectRef(rs.getLong(1), rs.getString(2), rs.getString(3))).stream()
                .filter(p -> permissions.permissionsOf(user, p.id()).contains(ProjectPermission.CREATE_ISSUE))
                .toList();
    }

    // --- plan --------------------------------------------------------------------------

    /** Values of a multi-valued field: the ids they get in the new project, and those dropped. */
    private record Mapped(Set<Long> kept, List<Named> dropped) {
        List<String> droppedNames() {
            return dropped.stream().map(Named::name).toList();
        }
    }

    private record Plan(ProjectRef target, Mapped components, Mapped affects, Mapped fixes, UserRef removedAssignee,
            IssueRef leavesEpic) {
        MovePreview preview() {
            return new MovePreview(target, components.droppedNames(), affects.droppedNames(), fixes.droppedNames(),
                    removedAssignee, leavesEpic);
        }
    }

    private Plan plan(IssueAccess access, ProjectAccess target) {
        Issue issue = access.issue();
        long id = issue.getId();
        long targetId = target.projectId();
        Mapped components = byName("component", targetId, fields.components(id));
        Mapped affects = byName("project_version", targetId, fields.versions(id, VersionKind.AFFECTS));
        Mapped fixes = byName("project_version", targetId, fields.versions(id, VersionKind.FIX));
        UserAccount assignee = issue.getAssignee();
        UserRef removedAssignee = assignee != null
                && !permissions.userHas(assignee.getId(), targetId, ProjectPermission.ASSIGNABLE_USER)
                ? UserRef.of(assignee) : null;
        Issue parent = issue.getParent();
        IssueRef epic = parent == null ? null : new IssueRef(parent.getId(),
                access.project().project().getProjectKey() + "-" + parent.getNumber(), parent.getSummary());
        Project p = target.project();
        return new Plan(new ProjectRef(p.getId(), p.getProjectKey(), p.getName()), components, affects, fixes,
                removedAssignee, epic);
    }

    /** Components or versions of the new project with the same name (ignoring case). */
    private Mapped byName(String table, long projectId, List<Named> values) {
        if (values.isEmpty()) {
            return new Mapped(Set.of(), List.of());
        }
        Map<String, Long> there = new HashMap<>();
        jdbc.query("select id, name from " + table + " where project_id = :project and lower(name) in (:names)",
                Map.of("project", projectId, "names", values.stream().map(v -> lower(v.name())).toList()),
                rs -> {
                    there.put(lower(rs.getString(2)), rs.getLong(1));
                });
        Set<Long> kept = new TreeSet<>();
        List<Named> dropped = new ArrayList<>();
        for (Named value : values) {
            Long match = there.get(lower(value.name()));
            if (match != null) {
                kept.add(match);
            } else {
                dropped.add(value);
            }
        }
        return new Mapped(kept, dropped);
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    // --- stub --------------------------------------------------------------------------

    /** The done status of the workflow that comes last (Closed in the default workflow). */
    private long doneStatus(long workflowId) {
        return jdbc.queryForList("""
                select s.id from workflow_status ws join status s on s.id = ws.status_id
                where ws.workflow_id = :workflow and s.category = 'DONE'
                order by s.position desc, s.id desc limit 1""", Map.of("workflow", workflowId), Long.class)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Workflow " + workflowId + " has no done status"));
    }

    /** Resolution Moved; the default resolution if an administrator removed or renamed it. */
    private long movedResolution() {
        return jdbc.queryForList("""
                select id from resolution order by lower(name) = 'moved' desc, is_default desc, position limit 1""",
                Map.of(), Long.class).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No resolutions"));
    }
}
