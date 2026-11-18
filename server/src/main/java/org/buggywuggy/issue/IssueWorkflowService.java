/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.buggywuggy.issue.IssueDtos.IssueResponse;
import org.buggywuggy.issue.IssueDtos.StatusRef;
import org.buggywuggy.issue.IssueDtos.TransitionRequest;
import org.buggywuggy.issue.IssueDtos.TransitionResponse;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;
import org.buggywuggy.issue.IssueService.IssueAccess;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.workflow.WorkflowService;
import org.buggywuggy.workflow.WorkflowService.Transition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Status changes through workflow transitions. Resolution handling is built in: moving to a
 * status in category DONE sets a resolution (the given one, else the current, else the
 * default); moving to any other status clears it.
 */
@Service
@Transactional
public class IssueWorkflowService {

    private final IssueService issueService;
    private final IssueRepository issues;
    private final StatusRepository statuses;
    private final ResolutionRepository resolutions;
    private final WorkflowService workflows;
    private final IssueHistory history;
    private final Clock clock;

    public IssueWorkflowService(IssueService issueService, IssueRepository issues, StatusRepository statuses,
            ResolutionRepository resolutions, WorkflowService workflows, IssueHistory history, Clock clock) {
        this.issueService = issueService;
        this.issues = issues;
        this.statuses = statuses;
        this.resolutions = resolutions;
        this.workflows = workflows;
        this.history = history;
        this.clock = clock;
    }

    /** Transitions the user can perform now; empty without TRANSITION_ISSUE or in an archived project. */
    @Transactional(readOnly = true)
    public List<TransitionResponse> available(BuggyWuggyPrincipal user, String idOrKey) {
        IssueAccess access = issueService.access(user, idOrKey);
        if (!access.project().has(ProjectPermission.TRANSITION_ISSUE) || access.project().project().isArchived()
                || issueService.isMovedStub(access.issue().getId())) {
            return List.of();
        }
        return transitionsOf(access).stream().map(t -> {
            Status to = statuses.getReferenceById(t.toStatusId());
            return new TransitionResponse(t.id(), t.name(), StatusRef.of(to),
                    to.getCategory() == Status.Category.DONE);
        }).toList();
    }

    public IssueResponse transition(BuggyWuggyPrincipal user, String idOrKey, TransitionRequest request) {
        IssueAccess access = issueService.access(user, idOrKey);
        access.project().require(ProjectPermission.TRANSITION_ISSUE);
        access.requireWritable();
        Issue issue = access.issue();
        if (request.version() != null && request.version() != issue.getVersion()) {
            throw conflict("error.conflict");
        }
        if (issueService.isMovedStub(issue.getId())) {
            throw conflict("issue.moved-stub", access.key());
        }
        Transition transition = transitionsOf(access).stream()
                .filter(t -> t.id() == request.transitionId())
                .findFirst()
                .orElseThrow(() -> badRequest("issue.transition-invalid"));

        Status from = issue.getStatus();
        Status to = statuses.getReferenceById(transition.toStatusId());
        boolean wasDone = from.getCategory() == Status.Category.DONE;
        boolean isDone = to.getCategory() == Status.Category.DONE;
        Resolution oldResolution = issue.getResolution();
        Resolution resolution;
        Instant now = clock.instant();

        if (isDone) {
            if (request.resolutionId() != null) {
                resolution = resolutions.findById(request.resolutionId()).filter(Resolution::isSelectable)
                        .orElseThrow(() -> badRequest("issue.resolution-invalid", request.resolutionId()));
            } else if (oldResolution != null) {
                resolution = oldResolution;
            } else {
                resolution = resolutions.findDefault().orElseThrow(() -> badRequest("issue.resolution-required"));
            }
            if (!wasDone || issue.getResolvedAt() == null) {
                issue.setResolvedAt(now);
            }
        } else {
            if (request.resolutionId() != null) {
                throw badRequest("issue.resolution-not-allowed");
            }
            resolution = null;
            issue.setResolvedAt(null);
        }

        Changes changes = new Changes();
        changes.record(Fields.STATUS, from.getId(), from.getName(), to.getId(), to.getName());
        changes.record(Fields.RESOLUTION, idOf(oldResolution), nameOf(oldResolution),
                idOf(resolution), nameOf(resolution));
        issue.setStatus(to);
        issue.setResolution(resolution);
        issue.setUpdatedAt(now);
        history.save(issue.getId(), user.id(), now, changes);
        issues.flush();
        return issueService.toResponse(access);
    }

    private List<Transition> transitionsOf(IssueAccess access) {
        return workflows.availableTransitions(issueService.workflowOf(access), access.issue().getStatus().getId());
    }

    private static Long idOf(Resolution r) {
        return r == null ? null : r.getId();
    }

    private static String nameOf(Resolution r) {
        return r == null ? null : r.getName();
    }
}
