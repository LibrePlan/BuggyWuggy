/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.bugzilla;

import java.util.List;
import java.util.Map;

/**
 * The parts of the Bugzilla REST API (5.0 and newer, JSON, snake_case) the importer reads.
 * Fields that only newer versions send (e.g. {@code type} since 5.2, {@code is_markdown}) may
 * be null; what the user may not see (e-mail addresses without login, {@code deadline} outside
 * the time-tracking group) is left out by Bugzilla.
 */
final class BugzillaApi {

    private BugzillaApi() {
    }

    record Version(String version) {
    }

    /** {@code name} is the login, usually an e-mail address. */
    record WhoAmI(Long id, String name, String realName) {
    }

    record UserDetail(Long id, String name, String realName, String email) {
    }

    record Users(List<UserDetail> users) {
    }

    record Component(String name, String description, Boolean isActive) {
    }

    record Milestone(String name, Boolean isActive) {
    }

    record Product(Long id, String name, String description, Boolean isActive, List<Component> components,
            List<Milestone> milestones) {
    }

    record Products(List<Product> products) {
    }

    /** {@code groups}: the groups a bug is restricted to; empty for public bugs. */
    record Bug(Long id, String product, String summary, String status, String resolution, Boolean isOpen,
            String severity, String priority, String component, String type, String targetMilestone,
            String creator, UserDetail creatorDetail, String assignedTo, UserDetail assignedToDetail,
            String creationTime, String lastChangeTime, String deadline, List<String> keywords, List<String> groups,
            List<Long> dependsOn, List<Long> blocks, Long dupeOf, List<String> seeAlso) {
    }

    record Bugs(List<Bug> bugs) {
    }

    /** {@code count} 0 is the description. {@code creator} is a login (older versions: author). */
    record Comment(Long id, Long count, String text, String creator, String author, String creationTime,
            Boolean isPrivate, Boolean isMarkdown, Long attachmentId) {
    }

    record CommentList(List<Comment> comments) {
    }

    /** By bug id. */
    record Comments(Map<String, CommentList> bugs) {
    }

    record Attachment(Long id, String fileName, Long size, String contentType, String creationTime, String creator,
            Boolean isPrivate, Boolean isObsolete, String summary, String data) {
    }

    /** By bug id. */
    record BugAttachments(Map<String, List<Attachment>> bugs) {
    }

    /** By attachment id. */
    record AttachmentData(Map<String, Attachment> attachments) {
    }

    /** {@code field_name}: API names (status, summary, …), in older versions database names (bug_status, …). */
    record Change(String fieldName, String removed, String added) {
    }

    record HistoryEntry(String when, String who, List<Change> changes) {
    }

    record BugHistory(Long id, List<HistoryEntry> history) {
    }

    record History(List<BugHistory> bugs) {
    }

    record FieldValue(String name, Boolean isOpen) {
    }

    record Field(String name, List<FieldValue> values) {
    }

    record Fields(List<Field> fields) {
    }
}
