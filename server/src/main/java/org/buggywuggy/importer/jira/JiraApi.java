/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.jira;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The parts of the Jira REST API (v2, the same on Cloud and Server/Data Center) the importer
 * reads, in camelCase. Times are kept as text: Jira writes offsets without a colon.
 */
final class JiraApi {

    private JiraApi() {
    }

    /** {@code deploymentType}: Cloud, Server or DataCenter. */
    record ServerInfo(String baseUrl, String version, String deploymentType, String serverTitle) {
    }

    /** Cloud: {@code accountId}; Server: {@code key} and {@code name} (the username). */
    record User(String accountId, String key, String name, String displayName, String emailAddress) {
    }

    record Permission(boolean havePermission) {
    }

    record Permissions(Map<String, Permission> permissions) {
    }

    record Project(String id, String key, String name, String description, Boolean isPrivate, Boolean archived) {
    }

    /** Cloud: /project/search. */
    record ProjectPage(List<Project> values, Boolean isLast) {
    }

    record Version(String id, String name, String description, Boolean released, String releaseDate) {
    }

    record Component(String name, String description) {
    }

    record Named(String id, String name) {
    }

    record IssueType(String name, Boolean subtask) {
    }

    record StatusCategory(String key) {
    }

    /** A status; its category {@code done} means the issue is finished. */
    record Status(String id, String name, StatusCategory statusCategory) {
    }

    record FieldSchema(String custom) {
    }

    record Field(String id, String name, FieldSchema schema) {
    }

    /** Server: startAt and total; Cloud (/search/jql): nextPageToken and isLast. */
    record SearchPage(int startAt, int maxResults, int total, List<JsonNode> issues, String nextPageToken,
            Boolean isLast) {
    }

    record IssueRef(String id, String key) {
    }

    record LinkType(String name, String inward, String outward) {
    }

    /** Only one of inwardIssue and outwardIssue is set: the other side of the link. */
    record IssueLink(String id, LinkType type, IssueRef inwardIssue, IssueRef outwardIssue) {
    }

    record Attachment(String id, String filename, User author, String created, long size, String mimeType,
            String content) {
    }

    /** {@code visibility}: restricted to a role or group; {@code jsdPublic} false: internal (Service Management). */
    record Comment(String id, User author, String body, String created, String updated, JsonNode visibility,
            Boolean jsdPublic) {
    }

    record CommentPage(int startAt, int maxResults, int total, List<Comment> comments) {
    }

    record Fields(String summary, String description, IssueType issuetype, Named priority, Status status,
            Named resolution, String resolutiondate, List<String> labels, List<Named> components,
            List<Version> fixVersions, User assignee, User reporter, User creator, String created, String updated,
            String duedate, CommentPage comment, List<Attachment> attachment, List<IssueLink> issuelinks,
            IssueRef parent, Named security) {
    }

    /** One change; {@code from}/{@code to} are ids (e.g. of a status or user), the strings are shown. */
    record ChangeItem(String field, String fieldId, String from, String fromString, String to,
            @JsonProperty("toString") String toText) {
    }

    record History(String id, User author, String created, List<ChangeItem> items) {
    }

    /** Cloud: /issue/{id}/changelog. */
    record ChangelogPage(int startAt, int maxResults, int total, Boolean isLast, List<History> values) {
    }

    /** Server: the changelog of an issue, with expand=changelog. */
    record Changelog(int startAt, int maxResults, int total, List<History> histories) {
    }

    record IssueWithChangelog(String id, Changelog changelog) {
    }

    record RemoteObject(String url, String title) {
    }

    record RemoteLink(String id, RemoteObject object, String relationship) {
    }
}
