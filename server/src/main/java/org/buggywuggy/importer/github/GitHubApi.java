/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** The parts of the GitHub REST API objects the importer reads (snake_case in JSON). */
final class GitHubApi {

    private GitHubApi() {
    }

    record User(long id, String login, String name, String email, String type, boolean siteAdmin) {
    }

    record Meta(String installedVersion) {
    }

    record Repository(long id, String fullName, String htmlUrl, String description, boolean hasIssues,
            int openIssuesCount, @JsonProperty("private") boolean isPrivate, boolean archived) {
    }

    record SearchResult(List<Repository> items) {
    }

    record Label(String name, String color, String description) {
    }

    record Milestone(String title, String description, String state, OffsetDateTime dueOn) {
    }

    record IssueType(String name) {
    }

    record SubIssuesSummary(int total) {
    }

    record DependenciesSummary(int blockedBy, int totalBlockedBy) {
    }

    /** Also pull requests: those have {@code pull_request}. */
    record Issue(long id, long number, String htmlUrl, String repositoryUrl, User user, String title, String body,
            List<Label> labels, Milestone milestone, List<User> assignees, String state, String stateReason,
            int comments, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime closedAt,
            JsonNode pullRequest, IssueType type, SubIssuesSummary subIssuesSummary,
            DependenciesSummary issueDependenciesSummary) {

        boolean isPullRequest() {
            return pullRequest != null && !pullRequest.isNull();
        }
    }

    record Rename(String from, String to) {
    }

    record CrossReference(String type, Issue issue) {
    }

    /** An entry of an issue's timeline: a comment ({@code commented}) or another event. */
    record TimelineEvent(long id, String event, User actor, User user, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, String body, Label label, User assignee, Milestone milestone, Rename rename,
            String stateReason, CrossReference source) {
    }
}
