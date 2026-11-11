/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import org.buggywuggy.common.ApiNullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.buggywuggy.user.UserDtos.GroupRef;
import org.buggywuggy.user.UserDtos.UserRef;

/** Request and response bodies of the project API. */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    // --- projects --------------------------------------------------------------------

    public record ProjectResponse(long id, String key, String name, @ApiNullable String description,
            @ApiNullable UserRef lead,
            Project.DefaultAssignee defaultAssignee, boolean archived, Instant createdAt) {

        public static ProjectResponse of(Project p) {
            return new ProjectResponse(p.getId(), p.getProjectKey(), p.getName(), p.getDescription(),
                    UserRef.of(p.getLead()), p.getDefaultAssignee(), p.isArchived(), p.getCreatedAt());
        }
    }

    public record CreateProjectRequest(
            @NotBlank @Pattern(regexp = Project.KEY_PATTERN, message = "{validation.project-key.pattern}") String key,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 10_000) String description,
            Long leadId,
            Project.DefaultAssignee defaultAssignee) {
    }

    /** Partial update: {@code null} fields are left unchanged. */
    public record UpdateProjectRequest(
            @Pattern(regexp = Project.KEY_PATTERN, message = "{validation.project-key.pattern}") String key,
            @Size(min = 1, max = 255) String name,
            @Size(max = 10_000) String description,
            Long leadId,
            Project.DefaultAssignee defaultAssignee,
            Boolean archived) {
    }

    public record MyProjectPermissionsResponse(Set<ProjectPermission> permissions) {
    }

    // --- components ------------------------------------------------------------------

    public record ComponentResponse(long id, String name, @ApiNullable String description, @ApiNullable UserRef lead,
            ProjectComponent.DefaultAssignee defaultAssignee) {

        public static ComponentResponse of(ProjectComponent c) {
            return new ComponentResponse(c.getId(), c.getName(), c.getDescription(), UserRef.of(c.getLead()),
                    c.getDefaultAssignee());
        }
    }

    public record CreateComponentRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 10_000) String description,
            Long leadId,
            ProjectComponent.DefaultAssignee defaultAssignee) {
    }

    /** Partial update: {@code null} fields are left unchanged; {@code clearLead: true} removes the lead. */
    public record UpdateComponentRequest(
            @Size(min = 1, max = 255) String name,
            @Size(max = 10_000) String description,
            Long leadId,
            Boolean clearLead,
            ProjectComponent.DefaultAssignee defaultAssignee) {
    }

    // --- versions --------------------------------------------------------------------

    public record VersionResponse(long id, String name, @ApiNullable String description,
            @ApiNullable LocalDate startDate, @ApiNullable LocalDate releaseDate, boolean released, boolean archived, int position) {

        public static VersionResponse of(ProjectVersion v) {
            return new VersionResponse(v.getId(), v.getName(), v.getDescription(), v.getStartDate(),
                    v.getReleaseDate(), v.isReleased(), v.isArchived(), v.getPosition());
        }
    }

    public record CreateVersionRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 10_000) String description,
            LocalDate startDate,
            LocalDate releaseDate,
            Boolean released) {
    }

    /**
     * Partial update: {@code null} fields are left unchanged. To clear a date, set
     * {@code clearStartDate} / {@code clearReleaseDate}.
     */
    public record UpdateVersionRequest(
            @Size(min = 1, max = 255) String name,
            @Size(max = 10_000) String description,
            LocalDate startDate,
            LocalDate releaseDate,
            Boolean clearStartDate,
            Boolean clearReleaseDate,
            Boolean released,
            Boolean archived) {
    }

    public record VersionOrderRequest(@NotNull List<Long> versionIds) {
    }

    // --- roles -----------------------------------------------------------------------

    public record ProjectRoleResponse(long id, String name, @ApiNullable String description) {

        public static ProjectRoleResponse of(ProjectRole r) {
            return new ProjectRoleResponse(r.getId(), r.getName(), r.getDescription());
        }
    }

    public record ProjectRoleMembersResponse(long id, String name, @ApiNullable String description, List<UserRef> users,
            List<GroupRef> groups) {
    }
}
