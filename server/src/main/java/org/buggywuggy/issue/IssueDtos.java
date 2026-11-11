/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

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

    public record IssueTypeRef(long id, String name, String icon, int hierarchyLevel) {
        static IssueTypeRef of(IssueType t) {
            return new IssueTypeRef(t.getId(), t.getName(), t.getIcon(), t.getHierarchyLevel());
        }
    }

    public record StatusRef(long id, String name, Status.Category category) {
        static StatusRef of(Status s) {
            return new StatusRef(s.getId(), s.getName(), s.getCategory());
        }
    }

    public record PriorityRef(long id, String name, String icon, String color) {
        static PriorityRef of(Priority p) {
            return p == null ? null : new PriorityRef(p.getId(), p.getName(), p.getIcon(), p.getColor());
        }
    }

    public record ResolutionRef(long id, String name) {
        static ResolutionRef of(Resolution r) {
            return r == null ? null : new ResolutionRef(r.getId(), r.getName());
        }
    }

    // --- issues ----------------------------------------------------------------------

    public record IssueResponse(
            long id, String key, ProjectRef project, IssueTypeRef issueType, StatusRef status,
            PriorityRef priority, ResolutionRef resolution, String summary, String description,
            UserRef reporter, UserRef assignee, UserRef creator, List<String> labels, List<Named> components,
            List<Named> affectsVersions, List<Named> fixVersions, LocalDate dueDate,
            Instant createdAt, Instant updatedAt, Instant resolvedAt, long version) {
    }

    public record IssueSummaryResponse(long id, String key, String summary, IssueTypeRef issueType,
            StatusRef status, PriorityRef priority, UserRef assignee, Instant updatedAt) {
    }

    public record CreateIssueRequest(
            @NotBlank String project,
            @NotNull Long issueTypeId,
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
                    || labels != null || componentIds != null || affectsVersionIds != null || fixVersionIds != null
                    || dueDate != null || Boolean.TRUE.equals(clearDueDate);
        }

        boolean changesAssignee() {
            return assigneeId != null || Boolean.TRUE.equals(unassign);
        }
    }

    // --- lookups ---------------------------------------------------------------------

    public record IssueTypeResponse(long id, String name, String description, String icon, int hierarchyLevel) {
        static IssueTypeResponse of(IssueType t) {
            return new IssueTypeResponse(t.getId(), t.getName(), t.getDescription(), t.getIcon(),
                    t.getHierarchyLevel());
        }
    }

    public record PriorityResponse(long id, String name, String description, String icon, String color,
            boolean isDefault) {
        static PriorityResponse of(Priority p) {
            return new PriorityResponse(p.getId(), p.getName(), p.getDescription(), p.getIcon(), p.getColor(),
                    p.isDefault());
        }
    }

    public record StatusResponse(long id, String name, String description, Status.Category category) {
        static StatusResponse of(Status s) {
            return new StatusResponse(s.getId(), s.getName(), s.getDescription(), s.getCategory());
        }
    }

    public record ResolutionResponse(long id, String name, String description, boolean isDefault) {
        static ResolutionResponse of(Resolution r) {
            return new ResolutionResponse(r.getId(), r.getName(), r.getDescription(), r.isDefault());
        }
    }
}
