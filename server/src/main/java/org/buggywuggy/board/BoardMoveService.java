/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.board;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;

import java.util.Map;
import java.util.Objects;
import org.buggywuggy.board.BoardDtos.Lane;
import org.buggywuggy.board.BoardDtos.MoveRequest;
import org.buggywuggy.issue.Issue;
import org.buggywuggy.issue.IssueDtos.TransitionRequest;
import org.buggywuggy.issue.IssueDtos.UpdateIssueRequest;
import org.buggywuggy.issue.IssueHistory;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;
import org.buggywuggy.issue.IssueService;
import org.buggywuggy.issue.IssueService.IssueAccess;
import org.buggywuggy.issue.IssueType;
import org.buggywuggy.issue.IssueWorkflowService;
import org.buggywuggy.project.ProjectAccess;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.project.ProjectService;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving an issue on a board, in one transaction: to another column through a workflow
 * transition, to another lane by changing the lane's field (assignee, epic or priority), and
 * to another place in the board order. Each part is checked and recorded in the history like
 * the same change made on the issue page.
 */
@Service
@Transactional
public class BoardMoveService {

    /** The distance between ranks when they are (re)numbered. */
    static final double STEP = 1024;

    private final ProjectService projects;
    private final IssueService issues;
    private final IssueWorkflowService workflow;
    private final IssueHistory history;
    private final NamedParameterJdbcTemplate jdbc;
    private final java.time.Clock clock;

    public BoardMoveService(ProjectService projects, IssueService issues, IssueWorkflowService workflow,
            IssueHistory history, NamedParameterJdbcTemplate jdbc, java.time.Clock clock) {
        this.projects = projects;
        this.issues = issues;
        this.workflow = workflow;
        this.history = history;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void move(BuggyWuggyPrincipal user, String projectIdOrKey, MoveRequest request) {
        ProjectAccess project = projects.access(user, projectIdOrKey).require(ProjectPermission.BROWSE_PROJECT);
        IssueAccess access = issues.access(user, request.issue());
        Issue issue = access.issue();
        if (issue.getProjectId() != project.projectId()) {
            throw badRequest("board.issue-other-project", request.issue());
        }
        if (project.project().isArchived()) {
            throw conflict("project.archived", project.project().getProjectKey());
        }
        if (request.version() != null && request.version() != issue.getVersion()) {
            throw conflict("error.conflict");
        }
        if (request.transitionId() != null) {
            workflow.transition(user, request.issue(),
                    new TransitionRequest(request.transitionId(), request.resolutionId(), null));
        }
        if (request.lane() != null) {
            UpdateIssueRequest update = laneUpdate(access, request.lane());
            if (update != null) {
                issues.update(user, request.issue(), update);
            }
        }
        if (request.after() != null || request.before() != null) {
            project.require(ProjectPermission.EDIT_ISSUE);
            rank(user, project, issue.getId(), request.after(), request.before());
        }
    }

    /** The change that puts the issue in the lane; null when it is already there. */
    private UpdateIssueRequest laneUpdate(IssueAccess access, Lane lane) {
        Issue issue = access.issue();
        String value = lane.value() == null || lane.value().isBlank() ? null : lane.value().strip();
        return switch (lane.by()) {
            case NONE -> null;
            case ASSIGNEE -> {
                Long assigneeId = value == null ? null : number(value);
                Long current = issue.getAssignee() == null ? null : issue.getAssignee().getId();
                yield Objects.equals(current, assigneeId) ? null
                        : update(null, null, null, assigneeId, assigneeId == null ? Boolean.TRUE : null);
            }
            case PRIORITY -> {
                if (value == null) {
                    throw badRequest("board.lane-no-priority");
                }
                long priorityId = number(value);
                yield issue.getPriority() != null && issue.getPriority().getId() == priorityId ? null
                        : update(null, null, priorityId, null, null);
            }
            case EPIC -> {
                Issue epic = epicOf(issue);
                Long target = value == null ? null : issues.access(access.project().user(), value).issue().getId();
                if (Objects.equals(epic == null ? null : epic.getId(), target)) {
                    yield null;
                }
                // Only standard issues have an epic of their own; sub-tasks follow their parent.
                if (issue.getIssueType().getHierarchyLevel() != IssueType.STANDARD) {
                    throw badRequest("board.lane-epic-fixed", issue.getIssueType().getName());
                }
                yield value == null ? update(null, Boolean.TRUE, null, null, null)
                        : update(value, null, null, null, null);
            }
        };
    }

    /** The epic of an issue's lane: an epic's own, a standard issue's parent, a sub-task's parent's parent. */
    private static Issue epicOf(Issue issue) {
        return switch (issue.getIssueType().getHierarchyLevel()) {
            case IssueType.EPIC -> issue;
            case IssueType.STANDARD -> issue.getParent();
            default -> issue.getParent() == null ? null : issue.getParent().getParent();
        };
    }

    private static UpdateIssueRequest update(String parent, Boolean clearParent, Long priorityId, Long assigneeId,
            Boolean unassign) {
        return new UpdateIssueRequest(null, null, null, null, parent, clearParent, priorityId, assigneeId, unassign,
                null, null, null, null, null, null, null);
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw badRequest("board.lane-invalid", value);
        }
    }

    // --- board order -----------------------------------------------------------------

    /**
     * Places the issue between {@code after} and {@code before} in the board order of the
     * project. Ranks are spread apart; when there is no room between two of them, all issues
     * of the project are renumbered first.
     */
    private void rank(BuggyWuggyPrincipal user, ProjectAccess project, long issueId, String afterKey,
            String beforeKey) {
        long projectId = project.projectId();
        // One reordering of a project at a time.
        jdbc.queryForObject("select id from project where id = :project for update", Map.of("project", projectId),
                Long.class);
        // Issues that were never moved get a rank after all others, in their board order.
        jdbc.update("""
                update issue set board_rank = r.rank
                from (select id, coalesce((select max(board_rank) from issue where project_id = :project), 0)
                             + :step * row_number() over (order by id) as rank
                      from issue where project_id = :project and board_rank is null) r
                where issue.id = r.id""", new MapSqlParameterSource().addValue("project", projectId)
                .addValue("step", STEP));

        Long afterId = neighbour(user, projectId, afterKey);
        Long beforeId = neighbour(user, projectId, beforeKey);
        if (Objects.equals(afterId, issueId) || Objects.equals(beforeId, issueId)) {
            throw badRequest("board.rank-self");
        }
        double old = rankOf(issueId);
        Double rank = between(afterId, beforeId);
        if (rank == null) {
            jdbc.update("""
                    update issue set board_rank = r.rank
                    from (select id, :step * row_number() over (order by board_rank, id) as rank
                          from issue where project_id = :project) r
                    where issue.id = r.id""", new MapSqlParameterSource().addValue("project", projectId)
                    .addValue("step", STEP));
            old = rankOf(issueId);
            rank = between(afterId, beforeId);
            if (rank == null) {
                throw badRequest("board.rank-invalid");
            }
        }
        jdbc.update("update issue set board_rank = :rank where id = :id", Map.of("rank", rank, "id", issueId));

        if (rank != old) {
            boolean higher = rank < old;
            Changes changes = new Changes();
            changes.record(Fields.RANK, null, null, higher ? "higher" : "lower",
                    higher ? "Ranked higher" : "Ranked lower");
            // Recorded, but nobody is notified of a new order.
            history.record(issueId, user.id(), clock.instant(), changes);
        }
    }

    private Long neighbour(BuggyWuggyPrincipal user, long projectId, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Issue issue = issues.access(user, key.strip()).issue();
        if (issue.getProjectId() != projectId) {
            throw badRequest("board.issue-other-project", key);
        }
        return issue.getId();
    }

    private double rankOf(long issueId) {
        return jdbc.queryForObject("select board_rank from issue where id = :id", Map.of("id", issueId), Double.class);
    }

    /** A rank between the two issues; null when there is no room (or they are out of order). */
    private Double between(Long afterId, Long beforeId) {
        if (afterId == null) {
            return rankOf(beforeId) - STEP;
        }
        if (beforeId == null) {
            return rankOf(afterId) + STEP;
        }
        double after = rankOf(afterId);
        double before = rankOf(beforeId);
        double middle = after + (before - after) / 2;
        return before - after > 1e-6 && middle > after && middle < before ? middle : null;
    }
}
