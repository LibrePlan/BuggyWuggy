/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.forgejo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** The parts of the Forgejo API objects the importer reads (snake_case in JSON). */
public final class ForgejoApi {

    private ForgejoApi() {
    }

    public record Version(String version) {
    }

    public record User(long id, String login, String fullName, String email, boolean isAdmin) {
    }

    public record Repository(long id, String fullName, String name, String htmlUrl, String description,
            boolean hasIssues, int openIssuesCount, User owner, @JsonProperty("private") boolean isPrivate,
            JsonNode externalTracker, boolean archived) {
    }

    public record SearchResult(boolean ok, List<Repository> data) {
    }

    public record Label(long id, String name, String color, String description, boolean exclusive) {
    }

    public record Milestone(long id, String title, String description, String state, OffsetDateTime dueOn,
            OffsetDateTime closedAt) {
    }

    public record Asset(long id, String name, long size, String uuid, String browserDownloadUrl,
            OffsetDateTime createdAt) {
    }

    public record RepositoryRef(long id, String name, String owner, String fullName) {
    }

    public record Issue(long id, long number, String htmlUrl, User user, String originalAuthor, String title,
            String body, List<Asset> assets, List<Label> labels, Milestone milestone, List<User> assignees,
            String state, int comments, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime closedAt,
            OffsetDateTime dueDate, JsonNode pullRequest, RepositoryRef repository) {

        public boolean isPullRequest() {
            return pullRequest != null && !pullRequest.isNull();
        }
    }

    public record Comment(long id, String htmlUrl, User user, String originalAuthor, String body, List<Asset> assets,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** An entry of an issue's timeline: a comment or an event such as a label change. */
    public record TimelineEvent(long id, String type, User user, String body, OffsetDateTime createdAt, Label label,
            User assignee, boolean removedAssignee, Milestone milestone, Milestone oldMilestone, String oldTitle,
            String newTitle, Issue refIssue, Issue dependentIssue, String refCommitSha) {
    }
}
