/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.gitlab;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** The parts of the GitLab REST API (v4) objects the importer reads (snake_case in JSON). */
final class GitLabApi {

    private GitLabApi() {
    }

    record Version(String version) {
    }

    record User(long id, String username, String name, String publicEmail, boolean isAdmin) {
    }

    record Project(long id, String pathWithNamespace, String webUrl, String description, String visibility,
            Boolean issuesEnabled, String issuesAccessLevel, int openIssuesCount, boolean archived) {
    }

    record Label(String name, String color, String description) {
    }

    record Milestone(String title, String description, String state, LocalDate dueDate) {
    }

    record References(String full) {
    }

    record Issue(long id, long iid, long projectId, String title, String description, String state,
            OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime closedAt, List<String> labels,
            Milestone milestone, List<User> assignees, User author, int userNotesCount, LocalDate dueDate,
            String webUrl, boolean confidential, String issueType, References references) {
    }

    /** A comment, or a system note ("changed title from …") that records a change. */
    record Note(long id, String body, User author, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            boolean system, boolean internal, boolean confidential) {
    }

    record LabelEvent(long id, User user, OffsetDateTime createdAt, Label label, String action) {
    }

    record MilestoneEvent(long id, User user, OffsetDateTime createdAt, Milestone milestone, String action) {
    }

    record StateEvent(long id, User user, OffsetDateTime createdAt, String state) {
    }

    record MergeRequest(long iid, String title, String webUrl, User author, OffsetDateTime createdAt,
            References references) {
    }

    /** An issue as listed by the links of another: {@code link_type} is relates_to, blocks or is_blocked_by. */
    record LinkedIssue(long iid, long projectId, String linkType, References references,
            OffsetDateTime linkCreatedAt) {
    }
}
