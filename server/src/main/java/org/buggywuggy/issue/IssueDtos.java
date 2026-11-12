/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import org.buggywuggy.common.ApiNullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.buggywuggy.issue.IssueFieldStore.Named;
import org.buggywuggy.user.UserDtos.UserRef;

/** Request and response bodies of the issue API. */
public final class IssueDtos {

    private IssueDtos() {
    }

    public static final String LABEL_PATTERN = "^\\S{1,255}$";

    // --- references ------------------------------------------------------------------

    public record ProjectRef(long id, String key, String name) {
    }

    public record IssueTypeRef(long id, String name, @ApiNullable String icon, int hierarchyLevel) {
        static IssueTypeRef of(IssueType t) {
            return new IssueTypeRef(t.getId(), t.getName(), t.getIcon(), t.getHierarchyLevel());
        }
    }

    public record StatusRef(long id, String name, Status.Category category) {
        static StatusRef of(Status s) {
            return new StatusRef(s.getId(), s.getName(), s.getCategory());
        }
    }

    public record PriorityRef(long id, String name, @ApiNullable String icon, @ApiNullable String color) {
        static PriorityRef of(Priority p) {
            return p == null ? null : new PriorityRef(p.getId(), p.getName(), p.getIcon(), p.getColor());
        }
    }

    public record ResolutionRef(long id, String name) {
        static ResolutionRef of(Resolution r) {
            return r == null ? null : new ResolutionRef(r.getId(), r.getName());
        }
    }

    /** Short reference to another issue (e.g. the parent). */
    public record IssueRef(long id, String key, String summary) {
    }

    /** A child issue: a sub-task of a standard issue, or an issue in an epic. */
    public record ChildIssueRef(long id, String key, String summary, IssueTypeRef issueType, StatusRef status) {
    }

    // --- issues ----------------------------------------------------------------------

    public record IssueResponse(
            long id, String key, ProjectRef project, IssueTypeRef issueType, StatusRef status,
            @ApiNullable PriorityRef priority, @ApiNullable ResolutionRef resolution, String summary,
            @ApiNullable String description, @ApiNullable UserRef reporter, @ApiNullable UserRef assignee,
            @ApiNullable UserRef creator, List<String> labels, List<Named> components,
            List<Named> affectsVersions, List<Named> fixVersions, @ApiNullable LocalDate dueDate,
            @ApiNullable IssueRef parent, List<ChildIssueRef> children,
            Instant createdAt, Instant updatedAt, @ApiNullable Instant resolvedAt, long version,
            @ApiNullable IssueOrigin origin, List<RemoteLink> remoteLinks) {
    }

    /** Where an imported issue came from: e.g. kind FORGEJO, key {@code owner/repo#12}. */
    public record IssueOrigin(String kind, @ApiNullable String baseUrl, String key, @ApiNullable String url) {
    }

    /** A link to something outside BuggyWuggy; {@code relation} is PULL_REQUEST or OTHER. */
    public record RemoteLink(long id, String url, String title, String relation, Instant createdAt) {
    }

    /** An issue in a list or search result. */
    public record IssueSummaryResponse(long id, String key, ProjectRef project, String summary,
            IssueTypeRef issueType, StatusRef status, @ApiNullable PriorityRef priority,
            @ApiNullable ResolutionRef resolution, @ApiNullable UserRef assignee, Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * {@code parent} (issue key or id) is required for sub-tasks and optional for standard
     * issues, where it must be an epic of the same project.
     */
    public record CreateIssueRequest(
            @NotBlank String project,
            @NotNull Long issueTypeId,
            String parent,
            @NotBlank @Size(max = 255) String summary,
            @Size(max = 100_000) String description,
            Long priorityId,
            Long assigneeId,
            Long reporterId,
            List<@Pattern(regexp = LABEL_PATTERN, message = "{validation.label.pattern}") String> labels,
            List<Long> componentIds,
            List<Long> affectsVersionIds,
            List<Long> fixVersionIds,
            LocalDate dueDate) {
    }

    /**
     * Partial update: {@code null} fields are left unchanged; list fields replace the current
     * values. When {@code version} is given, the update fails with 409 if the issue has been
     * changed in the meantime.
     */
    public record UpdateIssueRequest(
            Long version,
            @Size(min = 1, max = 255) String summary,
            @Size(max = 100_000) String description,
            Long issueTypeId,
            String parent,
            Boolean clearParent,
            Long priorityId,
            Long assigneeId,
            Boolean unassign,
            Long reporterId,
            List<@Pattern(regexp = LABEL_PATTERN, message = "{validation.label.pattern}") String> labels,
            List<Long> componentIds,
            List<Long> affectsVersionIds,
            List<Long> fixVersionIds,
            LocalDate dueDate,
            Boolean clearDueDate) {

        boolean editsFields() {
            return summary != null || description != null || issueTypeId != null || priorityId != null
                    || changesHierarchy()
                    || labels != null || componentIds != null || affectsVersionIds != null || fixVersionIds != null
                    || dueDate != null || Boolean.TRUE.equals(clearDueDate);
        }

        boolean changesAssignee() {
            return assigneeId != null || Boolean.TRUE.equals(unassign);
        }

        boolean changesHierarchy() {
            return issueTypeId != null || parent != null || Boolean.TRUE.equals(clearParent);
        }
    }

    // --- workflow --------------------------------------------------------------------

    public record TransitionResponse(long id, String name, StatusRef to, boolean setsResolution) {
    }

    /**
     * {@code resolutionId} may only be given when moving to a done status; if omitted there,
     * the current or default resolution is used.
     */
    public record TransitionRequest(@NotNull Long transitionId, Long resolutionId, Long version) {
    }

    // --- lookups ---------------------------------------------------------------------

    public record IssueTypeResponse(long id, String name, @ApiNullable String description, @ApiNullable String icon, int hierarchyLevel) {
        static IssueTypeResponse of(IssueType t) {
            return new IssueTypeResponse(t.getId(), t.getName(), t.getDescription(), t.getIcon(),
                    t.getHierarchyLevel());
        }
    }

    public record PriorityResponse(long id, String name, @ApiNullable String description, @ApiNullable String icon,
            @ApiNullable String color,
            boolean isDefault) {
        static PriorityResponse of(Priority p) {
            return new PriorityResponse(p.getId(), p.getName(), p.getDescription(), p.getIcon(), p.getColor(),
                    p.isDefault());
        }
    }

    public record StatusResponse(long id, String name, @ApiNullable String description, Status.Category category) {
        static StatusResponse of(Status s) {
            return new StatusResponse(s.getId(), s.getName(), s.getDescription(), s.getCategory());
        }
    }

    public record ResolutionResponse(long id, String name, @ApiNullable String description, boolean isDefault) {
        static ResolutionResponse of(Resolution r) {
            return new ResolutionResponse(r.getId(), r.getName(), r.getDescription(), r.isDefault());
        }
    }
}
