/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.forbidden;
import static org.buggywuggy.common.ApiException.notFound;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.buggywuggy.attachment.AttachmentFiles;
import org.buggywuggy.notification.IssueEvents;
import org.buggywuggy.common.PageResponse;
import org.buggywuggy.issue.IssueDtos.ChildIssueRef;
import org.buggywuggy.issue.IssueDtos.CreateIssueRequest;
import org.buggywuggy.issue.IssueDtos.IssueRef;
import org.buggywuggy.issue.IssueDtos.IssueResponse;
import org.buggywuggy.issue.IssueDtos.IssueSummaryResponse;
import org.buggywuggy.issue.IssueDtos.IssueTypeRef;
import org.buggywuggy.issue.IssueDtos.PriorityRef;
import org.buggywuggy.issue.IssueDtos.ProjectRef;
import org.buggywuggy.issue.IssueDtos.ResolutionRef;
import org.buggywuggy.issue.IssueDtos.StatusRef;
import org.buggywuggy.issue.IssueDtos.UpdateIssueRequest;
import org.buggywuggy.issue.IssueFieldStore.Named;
import org.buggywuggy.issue.IssueFieldStore.VersionKind;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;
import org.buggywuggy.project.ComponentRepository;
import org.buggywuggy.project.Project;
import org.buggywuggy.project.ProjectAccess;
import org.buggywuggy.project.ProjectComponent;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.project.ProjectPermissionService;
import org.buggywuggy.project.ProjectService;
import org.buggywuggy.search.IssueQuery;
import org.buggywuggy.search.IssueSearchService;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserAccount;
import org.buggywuggy.user.UserAccountRepository;
import org.buggywuggy.user.UserDtos.UserRef;
import org.buggywuggy.user.UserService;
import org.buggywuggy.workflow.WorkflowService;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IssueService {

    private static final Pattern ISSUE_KEY = Pattern.compile("^([A-Za-z][A-Za-z0-9_]{1,9})-(\\d{1,18})$");
    private static final Pattern NUMERIC = Pattern.compile("^\\d{1,18}$");

    /** An issue together with the current user's access to its project. */
    public record IssueAccess(Issue issue, ProjectAccess project) {

        public String key() {
            return project.project().getProjectKey() + "-" + issue.getNumber();
        }

        void requireWritable() {
            if (project.project().isArchived()) {
                throw conflict("project.archived", project.project().getProjectKey());
            }
        }
    }

    private final IssueRepository issues;
    private final IssueTypeRepository issueTypes;
    private final PriorityRepository priorities;
    private final StatusRepository statuses;
    private final ComponentRepository components;
    private final ProjectService projects;
    private final ProjectPermissionService permissions;
    private final UserService userService;
    private final UserAccountRepository users;
    private final WorkflowService workflows;
    private final IssueFieldStore fields;
    private final IssueHistory history;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final IssueSearchService search;
    private final AttachmentFiles attachmentFiles;
    private final IssueEvents events;

    public IssueService(IssueRepository issues, IssueTypeRepository issueTypes, PriorityRepository priorities,
            StatusRepository statuses, ComponentRepository components, ProjectService projects,
            ProjectPermissionService permissions, UserService userService, UserAccountRepository users,
            WorkflowService workflows, IssueFieldStore fields, IssueHistory history,
            NamedParameterJdbcTemplate jdbc, Clock clock, IssueSearchService search,
            AttachmentFiles attachmentFiles, IssueEvents events) {
        this.issues = issues;
        this.issueTypes = issueTypes;
        this.priorities = priorities;
        this.statuses = statuses;
        this.components = components;
        this.projects = projects;
        this.permissions = permissions;
        this.userService = userService;
        this.users = users;
        this.workflows = workflows;
        this.fields = fields;
        this.history = history;
        this.jdbc = jdbc;
        this.clock = clock;
        this.search = search;
        this.attachmentFiles = attachmentFiles;
        this.events = events;
    }

    // --- access ------------------------------------------------------------------------

    /**
     * Resolves an issue by id or key (also keys with a former project key) and checks that the
     * user may browse its project. Otherwise the issue is reported as not found.
     */
    @Transactional(readOnly = true)
    public IssueAccess access(BuggyWuggyPrincipal user, String idOrKey) {
        Issue issue = find(idOrKey).orElseThrow(() -> notFound("issue.not-found", idOrKey));
        Project project = projects.find(String.valueOf(issue.getProjectId())).orElseThrow();
        Set<ProjectPermission> granted = permissions.permissionsOf(user, project.getId());
        if (!granted.contains(ProjectPermission.BROWSE_PROJECT)) {
            throw notFound("issue.not-found", idOrKey);
        }
        return new IssueAccess(issue, new ProjectAccess(project, user, granted));
    }

    private Optional<Issue> find(String idOrKey) {
        if (NUMERIC.matcher(idOrKey).matches()) {
            return issues.findById(Long.parseLong(idOrKey));
        }
        Matcher m = ISSUE_KEY.matcher(idOrKey);
        if (!m.matches()) {
            return Optional.empty();
        }
        long number = Long.parseLong(m.group(2));
        return projects.find(m.group(1)).flatMap(project -> issues.findByProjectAndNumber(project.getId(), number)
                .or(() -> jdbc.queryForList(
                                "select issue_id from issue_key_history where project_id = :project and number = :number",
                                Map.of("project", project.getId(), "number", number), Long.class)
                        .stream().findFirst().flatMap(issues::findById)));
    }

    // --- queries -----------------------------------------------------------------------

    /** Whether the issue is the stub that stayed behind when an issue moved to another project. */
    @Transactional(readOnly = true)
    public boolean isMovedStub(long issueId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select moved_to_id is not null from issue where id = :id", Map.of("id", issueId), Boolean.class));
    }

    @Transactional(readOnly = true)
    public IssueResponse get(BuggyWuggyPrincipal user, String idOrKey) {
        return toResponse(access(user, idOrKey));
    }

    /** Issues of a project, most recently updated first. */
    @Transactional(readOnly = true)
    public PageResponse<IssueSummaryResponse> listByProject(BuggyWuggyPrincipal user, String projectIdOrKey,
            int page, int size) {
        ProjectAccess access = projects.access(user, projectIdOrKey).require(ProjectPermission.BROWSE_PROJECT);
        return search.search(user, IssueQuery.ofProject(access.projectId()), page, size);
    }

    @Transactional(readOnly = true)
    public List<IssueHistory.Entry> history(BuggyWuggyPrincipal user, String idOrKey) {
        return history.list(access(user, idOrKey).issue().getId());
    }

    // --- create ------------------------------------------------------------------------

    public IssueResponse create(BuggyWuggyPrincipal user, CreateIssueRequest request) {
        ProjectAccess access = projects.access(user, request.project()).require(ProjectPermission.CREATE_ISSUE);
        Project project = access.project();
        if (project.isArchived()) {
            throw conflict("project.archived", project.getProjectKey());
        }
        IssueType type = issueTypes.findById(request.issueTypeId())
                .orElseThrow(() -> badRequest("issue.type-invalid", request.issueTypeId()));
        Issue parent = request.parent() == null ? null : resolveParent(project.getId(), request.parent());
        validateParent(null, type, parent);
        long workflowId = workflows.workflowFor(project.getWorkflowSchemeId(), type.getId());
        Status status = statuses.getReferenceById(workflows.initialStatus(workflowId));
        Instant now = clock.instant();
        UserAccount creator = users.getReferenceById(user.id());

        Issue issue = new Issue(project.getId(), nextNumber(project.getId()), type, status,
                request.summary().trim(), creator, now);
        issue.setParent(parent);
        issue.setDescription(blankToNull(request.description()));
        issue.setDueDate(request.dueDate());
        issue.setPriority(request.priorityId() != null
                ? priorityById(request.priorityId())
                : priorities.findDefault().orElse(null));

        if (request.reporterId() == null || request.reporterId() == user.id()) {
            issue.setReporter(creator);
        } else {
            if (!access.has(ProjectPermission.ADMINISTER_PROJECT)) {
                throw forbidden("issue.reporter-permission");
            }
            issue.setReporter(userService.requireActiveUser(request.reporterId(), "issue.reporter-invalid"));
        }

        Set<Long> componentIds = validComponents(project.getId(), request.componentIds());
        if (request.assigneeId() != null) {
            access.require(ProjectPermission.ASSIGN_ISSUE);
            issue.setAssignee(assignable(project.getId(), request.assigneeId()));
        } else {
            issue.setAssignee(defaultAssignee(project, componentIds));
        }

        issues.save(issue);
        long id = issue.getId();
        fields.replaceLabels(id, normalizeLabels(request.labels()));
        fields.replaceComponents(id, componentIds);
        fields.replaceVersions(id, VersionKind.AFFECTS, validVersions(project.getId(), request.affectsVersionIds()));
        fields.replaceVersions(id, VersionKind.FIX, validVersions(project.getId(), request.fixVersionIds()));
        fields.addWatcher(id, user.id(), now);
        if (issue.getReporter() != null) {
            fields.addWatcher(id, issue.getReporter().getId(), now);
        }
        events.created(id, user.id(), now);
        return toResponse(new IssueAccess(issue, access));
    }

    // --- update ------------------------------------------------------------------------

    public IssueResponse update(BuggyWuggyPrincipal user, String idOrKey, UpdateIssueRequest request) {
        IssueAccess access = access(user, idOrKey);
        access.requireWritable();
        Issue issue = access.issue();
        ProjectAccess project = access.project();
        if (request.version() != null && request.version() != issue.getVersion()) {
            throw conflict("error.conflict");
        }
        if (request.editsFields()) {
            project.require(ProjectPermission.EDIT_ISSUE);
        }
        if (request.changesAssignee()) {
            project.require(ProjectPermission.ASSIGN_ISSUE);
        }
        if (request.reporterId() != null && !project.has(ProjectPermission.ADMINISTER_PROJECT)) {
            throw forbidden("issue.reporter-permission");
        }

        Changes changes = new Changes();
        if (request.summary() != null) {
            String summary = request.summary().trim();
            changes.record(Fields.SUMMARY, null, issue.getSummary(), null, summary);
            issue.setSummary(summary);
        }
        if (request.description() != null) {
            String description = blankToNull(request.description());
            changes.record(Fields.DESCRIPTION, null, issue.getDescription(), null, description);
            issue.setDescription(description);
        }
        if (request.changesHierarchy()) {
            changeTypeAndParent(access, request, changes);
        }
        if (request.priorityId() != null) {
            Priority priority = priorityById(request.priorityId());
            Priority old = issue.getPriority();
            changes.record(Fields.PRIORITY, old == null ? null : old.getId(), old == null ? null : old.getName(),
                    priority.getId(), priority.getName());
            issue.setPriority(priority);
        }
        if (request.changesAssignee()) {
            UserAccount assignee = Boolean.TRUE.equals(request.unassign())
                    ? null : assignable(project.projectId(), request.assigneeId());
            recordUser(changes, Fields.ASSIGNEE, issue.getAssignee(), assignee);
            issue.setAssignee(assignee);
        }
        if (request.reporterId() != null) {
            UserAccount reporter = userService.requireActiveUser(request.reporterId(), "issue.reporter-invalid");
            recordUser(changes, Fields.REPORTER, issue.getReporter(), reporter);
            issue.setReporter(reporter);
        }
        if (Boolean.TRUE.equals(request.clearDueDate()) || request.dueDate() != null) {
            LocalDate dueDate = Boolean.TRUE.equals(request.clearDueDate()) ? null : request.dueDate();
            changes.record(Fields.DUE_DATE, issue.getDueDate(), Objects.toString(issue.getDueDate(), null),
                    dueDate, Objects.toString(dueDate, null));
            issue.setDueDate(dueDate);
        }
        if (request.labels() != null) {
            List<String> old = fields.labels(issue.getId());
            List<String> labels = normalizeLabels(request.labels());
            if (!old.equals(labels)) {
                changes.record(Fields.LABELS, null, String.join(" ", old), null, String.join(" ", labels));
                fields.replaceLabels(issue.getId(), labels);
            }
        }
        if (request.componentIds() != null) {
            Set<Long> ids = validComponents(project.projectId(), request.componentIds());
            recordSetChange(changes, Fields.COMPONENT, fields.components(issue.getId()), ids);
            fields.replaceComponents(issue.getId(), ids);
        }
        if (request.affectsVersionIds() != null) {
            updateVersions(issue, VersionKind.AFFECTS, Fields.AFFECTS_VERSION, request.affectsVersionIds(), changes);
        }
        if (request.fixVersionIds() != null) {
            updateVersions(issue, VersionKind.FIX, Fields.FIX_VERSION, request.fixVersionIds(), changes);
        }

        if (!changes.isEmpty()) {
            Instant now = clock.instant();
            issue.setUpdatedAt(now);
            history.save(issue.getId(), user.id(), now, changes);
            // Flush now, so the response carries the new version for the client's next update.
            issues.flush();
        }
        return toResponse(access);
    }

    // --- delete ------------------------------------------------------------------------

    /** Deletes an issue. Its sub-tasks are deleted too; issues in a deleted epic lose their parent. */
    public void delete(BuggyWuggyPrincipal user, String idOrKey) {
        IssueAccess access = access(user, idOrKey);
        access.project().require(ProjectPermission.DELETE_ISSUE);
        access.requireWritable();
        Issue issue = access.issue();
        events.deleted(issue.getId(), user.id(), clock.instant());
        Map<String, Long> params = Map.of("id", issue.getId());
        boolean epic = issue.getIssueType().getHierarchyLevel() == IssueType.EPIC;
        // Files of the deleted issue, and of its sub-tasks (deleted too), are removed after commit.
        // Issues in an epic are kept, and so are their files.
        attachmentFiles.deleteAfterCommit(epic
                ? attachmentFiles.keysOfIssue(issue.getId())
                : attachmentFiles.keysOfIssueAndChildren(issue.getId()));
        if (epic) {
            jdbc.update("update issue set parent_id = null where parent_id = :id", params);
        } else {
            jdbc.update("delete from issue where parent_id = :id", params);
        }
        issues.delete(issue);
    }

    // --- helpers -----------------------------------------------------------------------

    /** Atomically reserves the next issue number of a project (row lock on the project). */
    long nextNumber(long projectId) {
        return jdbc.queryForObject(
                "update project set issue_counter = issue_counter + 1 where id = :id returning issue_counter",
                Map.of("id", projectId), Long.class);
    }

    /**
     * Changes issue type and/or parent together, because the hierarchy rules depend on both:
     * converting a task to a sub-task needs a parent in the same request, and converting a
     * sub-task to a task needs its parent cleared (or replaced by an epic).
     */
    private void changeTypeAndParent(IssueAccess access, UpdateIssueRequest request, Changes changes) {
        Issue issue = access.issue();
        IssueType oldType = issue.getIssueType();
        Issue oldParent = issue.getParent();

        IssueType type = request.issueTypeId() == null ? oldType : issueTypes.findById(request.issueTypeId())
                .orElseThrow(() -> badRequest("issue.type-invalid", request.issueTypeId()));
        Issue parent = Boolean.TRUE.equals(request.clearParent()) ? null
                : request.parent() != null ? resolveParent(issue.getProjectId(), request.parent())
                : oldParent;

        if (type.getHierarchyLevel() != oldType.getHierarchyLevel() && issues.hasChildren(issue.getId())) {
            throw badRequest("issue.type-change-has-children", type.getName());
        }
        validateParent(issue, type, parent);

        if (!type.getId().equals(oldType.getId())) {
            long workflowId = workflows.workflowFor(access.project().project().getWorkflowSchemeId(), type.getId());
            if (!workflows.containsStatus(workflowId, issue.getStatus().getId())) {
                throw badRequest("issue.type-change-workflow", type.getName());
            }
            changes.record(Fields.ISSUE_TYPE, oldType.getId(), oldType.getName(), type.getId(), type.getName());
            issue.setIssueType(type);
        }
        if (!Objects.equals(idOf(oldParent), idOf(parent))) {
            String projectKey = access.project().project().getProjectKey();
            changes.record(Fields.PARENT, idOf(oldParent), keyOf(projectKey, oldParent),
                    idOf(parent), keyOf(projectKey, parent));
            issue.setParent(parent);
        }
    }

    /**
     * Hierarchy rules: a sub-task needs a standard issue as parent; a standard issue may have an
     * epic as parent; an epic has no parent. Parent and child are always in the same project.
     */
    private static void validateParent(Issue issue, IssueType type, Issue parent) {
        if (type.getHierarchyLevel() == IssueType.EPIC) {
            if (parent != null) {
                throw badRequest("issue.parent-not-allowed", type.getName());
            }
            return;
        }
        if (parent == null) {
            if (type.isSubtask()) {
                throw badRequest("issue.subtask-requires-parent");
            }
            return;
        }
        int required = type.getHierarchyLevel() + 1;
        boolean self = issue != null && Objects.equals(issue.getId(), parent.getId());
        if (self || parent.getIssueType().getHierarchyLevel() != required) {
            throw badRequest("issue.parent-invalid", type.getName(), parent.getIssueType().getName());
        }
    }

    private Issue resolveParent(long projectId, String parentIdOrKey) {
        Issue parent = find(parentIdOrKey).orElseThrow(() -> badRequest("issue.parent-not-found", parentIdOrKey));
        if (parent.getProjectId() != projectId) {
            throw badRequest("issue.parent-other-project");
        }
        return parent;
    }

    private static Long idOf(Issue issue) {
        return issue == null ? null : issue.getId();
    }

    private static String keyOf(String projectKey, Issue issue) {
        return issue == null ? null : projectKey + "-" + issue.getNumber();
    }

    /** The workflow that applies to the issue (via its project's scheme and its type). */
    long workflowOf(IssueAccess access) {
        return workflows.workflowFor(access.project().project().getWorkflowSchemeId(),
                access.issue().getIssueType().getId());
    }

    private void updateVersions(Issue issue, VersionKind kind, String field, List<Long> requested, Changes changes) {
        Set<Long> ids = validVersions(issue.getProjectId(), requested);
        recordSetChange(changes, field, fields.versions(issue.getId(), kind), ids);
        fields.replaceVersions(issue.getId(), kind, ids);
    }

    /** One change item per added or removed value, like Jira does for components and versions. */
    private void recordSetChange(Changes changes, String field, List<Named> old, Set<Long> newIds) {
        Set<Long> oldIds = old.stream().map(Named::id).collect(Collectors.toSet());
        old.stream().filter(n -> !newIds.contains(n.id()))
                .forEach(n -> changes.record(field, n.id(), n.name(), null, null));
        if (newIds.stream().anyMatch(id -> !oldIds.contains(id))) {
            Map<Long, String> names = namesOf(field, newIds);
            newIds.stream().filter(id -> !oldIds.contains(id)).sorted()
                    .forEach(id -> changes.record(field, null, null, id, names.get(id)));
        }
    }

    private Map<Long, String> namesOf(String field, Collection<Long> ids) {
        String table = Fields.COMPONENT.equals(field) ? "component" : "project_version";
        return jdbc.query("select id, name from " + table + " where id in (:ids)", Map.of("ids", ids),
                rs -> {
                    Map<Long, String> names = new java.util.HashMap<>();
                    while (rs.next()) {
                        names.put(rs.getLong(1), rs.getString(2));
                    }
                    return names;
                });
    }

    static void recordUser(Changes changes, String field, UserAccount from, UserAccount to) {
        changes.record(field, from == null ? null : from.getId(), from == null ? null : from.getDisplayName(),
                to == null ? null : to.getId(), to == null ? null : to.getDisplayName());
    }

    private Priority priorityById(long id) {
        return priorities.findById(id).orElseThrow(() -> badRequest("issue.priority-invalid", id));
    }

    private UserAccount assignable(long projectId, long userId) {
        if (!permissions.userHas(userId, projectId, ProjectPermission.ASSIGNABLE_USER)) {
            throw badRequest("issue.assignee-not-assignable");
        }
        return users.getReferenceById(userId);
    }

    /**
     * The assignee for a new issue without explicit assignee: the first component (by name)
     * with its own rule decides, otherwise the project's default. Only assignable users are used.
     */
    private UserAccount defaultAssignee(Project project, Set<Long> componentIds) {
        UserAccount candidate = project.getDefaultAssignee() == Project.DefaultAssignee.PROJECT_LEAD
                ? project.getLead() : null;
        List<ProjectComponent> selected = components.findAllById(componentIds).stream()
                .sorted(Comparator.comparing(c -> c.getName().toLowerCase(java.util.Locale.ROOT)))
                .toList();
        for (ProjectComponent component : selected) {
            ProjectComponent.DefaultAssignee rule = component.getDefaultAssignee();
            if (rule != ProjectComponent.DefaultAssignee.PROJECT_DEFAULT) {
                candidate = switch (rule) {
                    case COMPONENT_LEAD -> component.getLead();
                    case PROJECT_LEAD -> project.getLead();
                    default -> null;
                };
                break;
            }
        }
        if (candidate == null
                || !permissions.userHas(candidate.getId(), project.getId(), ProjectPermission.ASSIGNABLE_USER)) {
            return null;
        }
        return candidate;
    }

    private Set<Long> validComponents(long projectId, List<Long> requested) {
        Set<Long> ids = requested == null ? Set.of() : new TreeSet<>(requested);
        Set<Long> valid = fields.componentsOfProject(projectId, ids);
        ids.stream().filter(id -> !valid.contains(id)).findFirst()
                .ifPresent(id -> { throw badRequest("issue.component-invalid", id); });
        return ids;
    }

    private Set<Long> validVersions(long projectId, List<Long> requested) {
        Set<Long> ids = requested == null ? Set.of() : new TreeSet<>(requested);
        Set<Long> valid = fields.versionsOfProject(projectId, ids);
        ids.stream().filter(id -> !valid.contains(id)).findFirst()
                .ifPresent(id -> { throw badRequest("issue.version-invalid", id); });
        return ids;
    }

    private static List<String> normalizeLabels(List<String> labels) {
        return labels == null ? List.of() : List.copyOf(new TreeSet<>(labels));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    IssueResponse toResponse(IssueAccess access) {
        Issue i = access.issue();
        Project p = access.project().project();
        long id = i.getId();
        String projectKey = p.getProjectKey();
        IssueRef parent = i.getParent() == null ? null : new IssueRef(i.getParent().getId(),
                keyOf(projectKey, i.getParent()), i.getParent().getSummary());
        List<ChildIssueRef> children = issues.findChildren(id).stream()
                .map(c -> new ChildIssueRef(c.getId(), keyOf(projectKey, c), c.getSummary(),
                        IssueTypeRef.of(c.getIssueType()), StatusRef.of(c.getStatus())))
                .toList();
        return new IssueResponse(id, access.key(),
                new ProjectRef(p.getId(), p.getProjectKey(), p.getName()),
                IssueTypeRef.of(i.getIssueType()), StatusRef.of(i.getStatus()), PriorityRef.of(i.getPriority()),
                ResolutionRef.of(i.getResolution()), i.getSummary(), i.getDescription(),
                UserRef.of(i.getReporter()), UserRef.of(i.getAssignee()), UserRef.of(i.getCreator()),
                fields.labels(id), fields.components(id),
                fields.versions(id, VersionKind.AFFECTS), fields.versions(id, VersionKind.FIX),
                i.getDueDate(), parent, children,
                i.getCreatedAt(), i.getUpdatedAt(), i.getResolvedAt(), i.getVersion(),
                fields.origin(id), fields.remoteLinks(id), fields.movedTo(id));
    }
}
