/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.project.Project;
import org.buggywuggy.user.UserDtos.UserRef;

/** Requests and responses of the import wizards, the same for every source. The token is never part of a response. */
public final class ImportDtos {

    private ImportDtos() {
    }

    public record ConnectionRequest(@NotBlank @Size(max = 500) String url, @Size(max = 500) String token) {
    }

    public record ConnectionResponse(String url, String version, @ApiNullable String login, boolean admin) {
    }

    public record RepositoryRequest(@NotBlank @Size(max = 500) String url, @Size(max = 500) String token,
            @Size(max = 200) String query) {
    }

    public record RepositoryInfo(String fullName, @ApiNullable String description, boolean isPrivate,
            boolean hasIssues, int openIssues, String htmlUrl) {
    }

    public record AnalysisRequest(@NotBlank @Size(max = 500) String url, @Size(max = 500) String token,
            @NotBlank @Size(max = 300) String repository) {
    }

    /** What a label of the source becomes in BuggyWuggy. */
    public enum LabelTarget { LABEL, ISSUE_TYPE, PRIORITY, IGNORE }

    /** {@code id} is the issue type or priority for those targets. */
    public record LabelRule(@NotNull LabelTarget target, @ApiNullable Long id) {
    }

    public record LabelInfo(String name, String color, @ApiNullable String description, int issues,
            LabelRule suggestion) {
    }

    public record UserInfo(String id, String login, @ApiNullable String name, int issues,
            @ApiNullable UserRef suggestion) {
    }

    public record Analysis(RepositoryInfo repository, int issues, int openIssues, int closedIssues, int comments,
            int milestones, int alreadyImported, List<LabelInfo> labels, List<UserInfo> users) {
    }

    public record NewProject(
            @NotBlank @Pattern(regexp = Project.KEY_PATTERN, message = "{validation.project-key.pattern}") String key,
            @NotBlank @Size(max = 255) String name) {
    }

    /**
     * Starts an import into {@code projectId}, or into a new project. {@code users} maps
     * user ids of the source to local accounts; other users are matched automatically: by
     * e-mail address, and by username unless {@code matchUsernames} is false.
     */
    public record StartRequest(@NotBlank @Size(max = 500) String url, @Size(max = 500) String token,
            @NotBlank @Size(max = 300) String repository, Long projectId, @Valid NewProject newProject,
            @NotNull Long issueTypeId, Map<String, @Valid LabelRule> labels, Map<String, Long> users,
            @ApiNullable Boolean matchUsernames) {
    }

    public record RetryRequest(@Size(max = 500) String token) {
    }

    /** Stored with the job; deliberately without the token. */
    public record Options(String repository, long issueTypeId, Map<String, LabelRule> labels, Map<String, Long> users,
            boolean matchUsernames) {
    }
}
