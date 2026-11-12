/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.buggywuggy.mail.TemplateEngine.Keyword;

/**
 * The mails BuggyWuggy sends, with the keywords each template may use. Issue mails are
 * bundles of changes (see NotificationDispatcher), so they share one set of keywords; the
 * template is chosen by the most important change in the bundle.
 */
public enum MailTemplateKey {

    ISSUE_CREATED("issue-created"),
    ISSUE_UPDATED("issue-updated"),
    ISSUE_ASSIGNED("issue-assigned"),
    ISSUE_TRANSITIONED("issue-transitioned"),
    ISSUE_COMMENTED("issue-commented"),
    ISSUE_ATTACHMENT_ADDED("issue-attachment-added"),
    ISSUE_DELETED("issue-deleted"),
    TEST_MAIL("test-mail");

    private static final List<Keyword> COMMON = List.of(
            new Keyword("recipient.name", false),
            new Keyword("recipient.username", false),
            new Keyword("instance.url", false),
            new Keyword("profile.url", false));

    private static final List<Keyword> ISSUE = List.of(
            new Keyword("actor.name", false),
            new Keyword("issue.key", false),
            new Keyword("issue.summary", false),
            new Keyword("issue.url", false),
            new Keyword("issue.type", false),
            new Keyword("issue.status", false),
            new Keyword("issue.priority", false),
            new Keyword("issue.assignee", false),
            new Keyword("issue.reporter", false),
            new Keyword("issue.description", true),
            new Keyword("project.key", false),
            new Keyword("project.name", false),
            new Keyword("changes", true),
            new Keyword("comments", true),
            new Keyword("attachments", false));

    private final String key;

    MailTemplateKey(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** Keywords in the order they are documented, by name. */
    public Map<String, Keyword> keywords() {
        Map<String, Keyword> result = new LinkedHashMap<>();
        if (this != TEST_MAIL) {
            ISSUE.forEach(k -> result.put(k.name(), k));
        }
        COMMON.forEach(k -> result.put(k.name(), k));
        return result;
    }

    public static Optional<MailTemplateKey> of(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }
}
