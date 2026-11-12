/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.gitlab;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the system notes GitLab writes for changes it has no event API for: the title, the
 * assignees and the due date. The texts are English and have been stable for years, e.g.
 * {@code changed title from **Crash{- on start-}** to **Crash{+ at boot+}**},
 * {@code assigned to @bob and unassigned @alice}, {@code changed due date to January 5, 2026}.
 */
final class GitLabNotes {

    private static final Pattern TITLE = Pattern.compile("(?s)^changed title from \\*\\*(.*)\\*\\* to \\*\\*(.*)\\*\\*$");
    private static final Pattern ADDED = Pattern.compile("\\{\\+(.*?)\\+\\}", Pattern.DOTALL);
    private static final Pattern REMOVED = Pattern.compile("\\{-(.*?)-\\}", Pattern.DOTALL);
    private static final Pattern ASSIGNED =
            Pattern.compile("(?<!un)(?:re)?assigned to ((?:@[\\w.\\-]+(?:,\\s*|\\s+and\\s+)?)+)");
    private static final Pattern UNASSIGNED = Pattern.compile("unassigned ((?:@[\\w.\\-]+(?:,\\s*|\\s+and\\s+)?)+)");
    private static final Pattern USERNAME = Pattern.compile("@([\\w.\\-]*[\\w\\-])");
    private static final Pattern DUE = Pattern.compile("^changed due date to (.+?)\\.?$");
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    record Title(String from, String to) {
    }

    record Assignment(List<String> assigned, List<String> unassigned) {
    }

    private GitLabNotes() {
    }

    static Optional<Title> title(String body) {
        Matcher m = TITLE.matcher(body.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        String from = REMOVED.matcher(ADDED.matcher(m.group(1)).replaceAll("")).replaceAll("$1");
        String to = ADDED.matcher(REMOVED.matcher(m.group(2)).replaceAll("")).replaceAll("$1");
        return Optional.of(new Title(from, to));
    }

    static Optional<Assignment> assignment(String body) {
        String text = body.strip();
        if (!text.startsWith("assigned to ") && !text.startsWith("reassigned to ")
                && !text.startsWith("unassigned ")) {
            return Optional.empty();
        }
        List<String> assigned = new ArrayList<>();
        List<String> unassigned = new ArrayList<>();
        Matcher a = ASSIGNED.matcher(text);
        while (a.find()) {
            usernames(a.group(1), assigned);
        }
        Matcher u = UNASSIGNED.matcher(text);
        while (u.find()) {
            usernames(u.group(1), unassigned);
        }
        return assigned.isEmpty() && unassigned.isEmpty() ? Optional.empty()
                : Optional.of(new Assignment(assigned, unassigned));
    }

    private static void usernames(String list, List<String> into) {
        Matcher m = USERNAME.matcher(list);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    /** Empty when the note is not about the due date; an empty inner value when it was removed. */
    static Optional<Optional<LocalDate>> dueDate(String body) {
        String text = body.strip();
        if (text.equals("removed due date")) {
            return Optional.of(Optional.empty());
        }
        Matcher m = DUE.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        String value = m.group(1).strip();
        try {
            return Optional.of(Optional.of(LocalDate.parse(value, LONG_DATE)));
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(Optional.of(LocalDate.parse(value)));
            } catch (DateTimeParseException e2) {
                return Optional.empty();
            }
        }
    }
}
