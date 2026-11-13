/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.ImportWriter.SourceUser;

/**
 * One project in another tracker (a Forgejo or GitHub repository), as the import engine sees
 * it. Each kind of tracker implements this on top of its API; everything after that — users,
 * mapping, history, texts, links — is shared.
 */
public interface Source {

    /** {@code import_source.kind}, e.g. FORGEJO. */
    String kind();

    /** The name shown to people, e.g. "GitHub". */
    String displayName();

    /** The server, as stored with the source (e.g. https://codeberg.org). */
    String baseUrl();

    /** The web address issues and pull requests are under, e.g. https://github.com. */
    String webUrl();

    /** The project in the source, e.g. owner/repository. */
    String path();

    /** The host placeholders are named after: {@code login@host}. */
    String host();

    /** Attachment links in texts, with the attachment's key as group 1; null when none. */
    Pattern attachmentLinks();

    /** The key of an attachment as stored, for a key {@link #attachmentLinks()} found. */
    default String attachmentKey(String found) {
        return found;
    }

    /**
     * How the source names an issue, e.g. owner/repo#12 or (Jira) ABC-12. Stored as the
     * origin of an imported issue, and used to find it again.
     */
    default String reference(String repository, long number) {
        return repository + "#" + number;
    }

    /** Links to issues in texts, with the repository as group 1 and the number as group 2. */
    default Pattern issueLinks() {
        return ImportText.defaultIssueLinks(webUrl());
    }

    /**
     * References like {@code ABC-12} in texts (project group 1, number group 2), for sources
     * that do not use {@code #12} and {@code owner/repo#12}; null for those.
     */
    default Pattern issueKeys() {
        return null;
    }

    /** The web address of an issue, for references to issues that were not imported. */
    default String issueUrl(String repository, long number) {
        return webUrl() + "/" + repository + "/issues/" + number;
    }

    /** The original of an attachment link that was not copied, as an absolute link. */
    default String attachmentUrl(String found) {
        return found.startsWith("/") ? webUrl() + found : found;
    }

    /** Stops waits and retries when the job is cancelled, and reports waits. */
    void watch(BooleanSupplier cancelled, ReadOnlyHttp.Waits waits);

    RepositoryInfo info();

    List<SourceLabel> labels();

    /** The user with name and public e-mail address, as far as the source tells; may be the same. */
    default SourceUser profile(SourceUser user) {
        return user;
    }

    List<SourceMilestone> milestones();

    /** All issues (no pull requests), in any order. */
    List<SourceIssue> issues();

    /** What the engine needs beyond the list: comments, events, dependencies, sub-issues. */
    SourceDetails details(SourceIssue issue);

    /** Only the comments, for an issue an earlier run imported. */
    default List<SourceComment> comments(SourceIssue issue) {
        return details(issue).comments();
    }

    /** Opens an attachment; {@code maxBytes} as in {@link ReadOnlyHttp#download}. */
    ReadOnlyHttp.Download download(SourceAttachment attachment, long maxBytes) throws IOException;

    // --- model -----------------------------------------------------------------------------

    /** Why an issue was closed. */
    enum CloseReason { COMPLETED, NOT_PLANNED, DUPLICATE }

    record SourceLabel(String name, String color, String description) {
    }

    record SourceMilestone(String title, String description, LocalDate due, boolean closed) {
    }

    /**
     * {@code key} identifies the file in the source (e.g. a UUID or a URL); {@code name} and
     * {@code size} (-1) may be unknown until it is downloaded.
     */
    record SourceAttachment(String key, String name, long size, String url, Instant createdAt) {
    }

    /** An issue in this or another project of the source; {@code repository} null for this one. */
    record SourceRef(String repository, long number) {
    }

    /**
     * {@code labels} are label names; an issue type of the source is passed as the label
     * {@code "type:" + name}. {@code commentCount} may be -1 when unknown.
     */
    record SourceIssue(String externalId, long number, String url, SourceUser author, String title, String body,
            List<String> labels, String milestone, List<SourceUser> assignees, boolean closed,
            CloseReason closeReason, Instant createdAt, Instant updatedAt, Instant closedAt, LocalDate due,
            int commentCount, List<SourceAttachment> attachments) {
    }

    record SourceComment(String externalId, SourceUser author, String body, Instant createdAt, Instant updatedAt,
            List<SourceAttachment> attachments) {
    }

    /**
     * Something that happened to an issue. Only the fields of its kind are set:
     * <ul>
     * <li>LABEL_ADDED, LABEL_REMOVED: {@code value} = label name</li>
     * <li>ASSIGNED, UNASSIGNED: {@code user}</li>
     * <li>MILESTONE: {@code oldValue}, {@code value} (titles, null for none)</li>
     * <li>RENAMED: {@code oldValue}, {@code value}</li>
     * <li>DUE_DATE: {@code oldValue}, {@code value} (ISO dates, null for none)</li>
     * <li>CLOSED: {@code closeReason} (null when unknown); REOPENED</li>
     * <li>PULL_REQUEST: {@code value} = URL, {@code oldValue} = title</li>
     * <li>WEB_LINK: as PULL_REQUEST, for other links (e.g. a page in a wiki)</li>
     * </ul>
     */
    record SourceEvent(Kind kind, SourceUser author, Instant at, String oldValue, String value, SourceUser user,
            CloseReason closeReason) {

        public enum Kind {
            LABEL_ADDED, LABEL_REMOVED, ASSIGNED, UNASSIGNED, MILESTONE, RENAMED, DUE_DATE, CLOSED, REOPENED,
            PULL_REQUEST, WEB_LINK
        }
    }

    /** A dependency: {@code blocker} blocks this issue; author and time when known. */
    record SourceDependency(SourceRef blocker, SourceUser author, Instant at) {
    }

    /**
     * A link from this issue to another, read as "this issue {@code <outward description of
     * type>} other": e.g. Duplicate means this issue duplicates the other. {@code type} is the
     * name of a link type; unknown names become Relates.
     */
    record SourceLink(String type, SourceRef other) {
    }

    /** {@code links}: links other than dependencies and sub-issues. */
    record SourceDetails(List<SourceComment> comments, List<SourceEvent> events, List<SourceDependency> blockedBy,
            List<SourceRef> children, List<SourceLink> links) {
    }
}
