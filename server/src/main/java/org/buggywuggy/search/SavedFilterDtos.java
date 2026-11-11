/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.search;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.issue.IssueDtos.ProjectRef;
import org.buggywuggy.user.UserDtos.UserRef;

public final class SavedFilterDtos {

    private SavedFilterDtos() {
    }

    /** Who besides the owner can use a filter. {@code PROJECT}: everyone who can browse the project. */
    public enum SharedWith { PRIVATE, LOGGED_IN, PROJECT }

    /** {@code mine}: whether the current user owns (and may change) the filter. */
    public record FilterResponse(long id, String name, @ApiNullable String description, IssueQuery query,
            SharedWith sharedWith, @ApiNullable ProjectRef project, UserRef owner, boolean mine,
            Instant createdAt, Instant updatedAt) {
    }

    /** {@code projectId} is required when sharing with a project, and only allowed then. */
    public record CreateFilterRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 10_000) String description,
            @NotNull @Valid IssueQuery query,
            SharedWith sharedWith,
            Long projectId) {
    }

    /** Partial update: {@code null} fields are left unchanged. */
    public record UpdateFilterRequest(
            @Size(min = 1, max = 255) String name,
            @Size(max = 10_000) String description,
            @Valid IssueQuery query,
            SharedWith sharedWith,
            Long projectId) {
    }
}
