/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.redmine;

import java.util.List;

/**
 * The parts of the Redmine REST API (JSON, snake_case) the importer reads. Fields that only
 * newer versions send (e.g. {@code is_public}, {@code closed_on}, {@code is_closed}) may be
 * null.
 */
final class RedmineApi {

    private RedmineApi() {
    }

    /** A reference to a user, group, tracker, version, …: id and name. */
    record Named(Long id, String name) {
    }

    /** {@code login} and {@code mail} are only shown to administrators (and the user). */
    record User(Long id, String login, String firstname, String lastname, String mail, Boolean admin) {
    }

    record UserWrapper(User user) {
    }

    record Module(String name) {
    }

    record Project(Long id, String name, String identifier, String description, Boolean isPublic, Integer status,
            List<Module> enabledModules) {
    }

    record ProjectWrapper(Project project) {
    }

    record ProjectPage(List<Project> projects, int totalCount, int offset, int limit) {
    }

    /** {@code is_closed} is in the status of an issue since Redmine 5.1, and in /issue_statuses. */
    record Status(Long id, String name, Boolean isClosed) {
    }

    record Statuses(List<Status> issueStatuses) {
    }

    record Trackers(List<Named> trackers) {
    }

    record Priorities(List<Named> issuePriorities) {
    }

    record Categories(List<Named> issueCategories) {
    }

    /** {@code status}: open, locked or closed. Shared versions belong to another project. */
    record Version(Long id, String name, String description, String status, String dueDate, Named project) {
    }

    record Versions(List<Version> versions) {
    }

    record VersionWrapper(Version version) {
    }

    record Attachment(Long id, String filename, Long filesize, String contentType, String contentUrl, Named author,
            String createdOn) {
    }

    /**
     * {@code relation_type}: relates, duplicates, blocks, precedes, copied_to (Redmine stores
     * the reversed types duplicated, blocked, follows and copied_from the other way round).
     */
    record Relation(Long id, Long issueId, Long issueToId, String relationType) {
    }

    /** {@code property}: attr, cf, attachment or relation; {@code name}: e.g. status_id. */
    record Detail(String property, String name, String oldValue, String newValue) {
    }

    record Journal(Long id, Named user, String notes, String createdOn, String updatedOn, Boolean privateNotes,
            List<Detail> details) {
    }

    record Parent(Long id) {
    }

    record Issue(Long id, Named project, Named tracker, Status status, Named priority, Named author,
            Named assignedTo, Named category, Named fixedVersion, Parent parent, String subject, String description,
            String dueDate, Boolean isPrivate, String createdOn, String updatedOn, String closedOn,
            List<Attachment> attachments, List<Relation> relations, List<Journal> journals) {
    }

    record IssuePage(List<Issue> issues, int totalCount, int offset, int limit) {
    }

    record IssueWrapper(Issue issue) {
    }
}
