/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.forgejo;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.buggywuggy.importer.ImportWriter.Named;
import org.buggywuggy.importer.forgejo.ForgejoApi.Issue;
import org.buggywuggy.importer.forgejo.ForgejoApi.TimelineEvent;
import org.buggywuggy.importer.forgejo.ForgejoApi.User;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;

/**
 * Turns an issue's Forgejo timeline into BuggyWuggy history. Forgejo records events ("label
 * X added"), BuggyWuggy records old and new values; so the importer starts at the issue as it
 * is now and walks the timeline backwards, undoing one event at a time.
 *
 * <p>Events within a few seconds by the same person form one history entry, like one edit in
 * BuggyWuggy. Events when the issue was created describe its first state and are not history.
 */
final class ForgejoHistory {

    private static final Duration SAME_EDIT = Duration.ofSeconds(3);

    /** How the importer maps the issue's values. */
    interface Mapping {

        /** The BuggyWuggy label for a Forgejo label, or null when it maps to something else. */
        String label(String forgejoLabel);

        /** Issue type for a set of Forgejo labels. */
        Named issueType(Set<String> forgejoLabels);

        /** Priority for a set of Forgejo labels; may be null. */
        Named priority(Set<String> forgejoLabels);

        /** The local user for a Forgejo user. */
        Named user(User user);

        /** The project version for a milestone title. */
        Named version(String milestoneTitle);

        Named status(boolean closed, boolean reopened);

        Named resolution(boolean closed);
    }

    /** One history entry. {@code author} is null for events without a user. */
    record Entry(User author, Instant at, Changes changes) {
    }

    /** A pull request that referenced the issue. */
    record PullRequest(String url, String title, User author, Instant at) {
    }

    record Result(List<Entry> entries, List<PullRequest> pullRequests, boolean reopened) {
    }

    /** The values history is about; Forgejo labels and assignees as Forgejo names. */
    private static final class State {
        final Set<String> labels;
        final List<User> assignees;
        String milestone;
        String title;
        LocalDate due;
        boolean closed;
        boolean reopened;

        State(Set<String> labels, List<User> assignees, String milestone, String title, LocalDate due,
                boolean closed, boolean reopened) {
            this.labels = labels;
            this.assignees = assignees;
            this.milestone = milestone;
            this.title = title;
            this.due = due;
            this.closed = closed;
            this.reopened = reopened;
        }

        State copy() {
            return new State(new LinkedHashSet<>(labels), new ArrayList<>(assignees), milestone, title, due, closed,
                    reopened);
        }
    }

    private ForgejoHistory() {
    }

    static Result of(Issue issue, List<TimelineEvent> timeline, Mapping mapping) {
        List<TimelineEvent> events = timeline.stream()
                .filter(e -> e.createdAt() != null && e.type() != null)
                .sorted(Comparator.comparing(TimelineEvent::createdAt).thenComparingLong(TimelineEvent::id))
                .toList();
        boolean reopened = events.stream().anyMatch(e -> e.type().equals("reopen"));

        List<PullRequest> pullRequests = new ArrayList<>();
        for (TimelineEvent e : events) {
            Issue ref = e.refIssue();
            if ((e.type().equals("pull_ref") || e.type().equals("comment_ref")) && ref != null && ref.isPullRequest()
                    && ref.htmlUrl() != null && pullRequests.stream().noneMatch(p -> p.url().equals(ref.htmlUrl()))) {
                String repo = ref.repository() == null ? "" : ref.repository().fullName();
                pullRequests.add(new PullRequest(ref.htmlUrl(), repo + "#" + ref.number() + " " + ref.title(),
                        e.user(), e.createdAt().toInstant()));
            }
        }

        // Group events that belong together.
        List<List<TimelineEvent>> groups = new ArrayList<>();
        for (TimelineEvent e : events) {
            if (!changesState(e) && !isPullRequestRef(e, pullRequests)) {
                continue;
            }
            List<TimelineEvent> lastGroup = groups.isEmpty() ? null : groups.getLast();
            if (lastGroup != null && sameAuthor(lastGroup.getFirst(), e)
                    && Duration.between(lastGroup.getLast().createdAt(), e.createdAt()).compareTo(SAME_EDIT) <= 0) {
                lastGroup.add(e);
            } else {
                groups.add(new ArrayList<>(List.of(e)));
            }
        }

        State after = new State(
                issue.labels() == null ? new LinkedHashSet<>()
                        : new LinkedHashSet<>(issue.labels().stream().map(ForgejoApi.Label::name).toList()),
                issue.assignees() == null ? new ArrayList<>() : new ArrayList<>(issue.assignees()),
                issue.milestone() == null ? null : issue.milestone().title(),
                issue.title(),
                issue.dueDate() == null ? null : issue.dueDate().toLocalDate(),
                "closed".equals(issue.state()),
                reopened);
        Instant created = issue.createdAt().toInstant();
        List<Entry> entries = new ArrayList<>();
        for (int g = groups.size() - 1; g >= 0; g--) {
            List<TimelineEvent> group = groups.get(g);
            State before = after.copy();
            for (int i = group.size() - 1; i >= 0; i--) {
                undo(group.get(i), before);
            }
            before.reopened = events.stream().anyMatch(e -> e.type().equals("reopen")
                    && e.createdAt().isBefore(group.getFirst().createdAt()));
            TimelineEvent first = group.getFirst();
            boolean atCreation = Duration.between(created, first.createdAt().toInstant()).compareTo(SAME_EDIT) <= 0;
            if (!atCreation) {
                Changes changes = diff(before, after, mapping);
                for (TimelineEvent e : group) {
                    pullRequests.stream().filter(p -> isPullRequestRef(e, List.of(p))).findFirst()
                            .ifPresent(p -> changes.record(Fields.REMOTE_LINK, null, null, p.url(), p.title()));
                }
                if (!changes.isEmpty()) {
                    entries.add(new Entry(first.user(), first.createdAt().toInstant(), changes));
                }
            }
            after = before;
        }
        return new Result(entries.reversed(), pullRequests, reopened);
    }

    private static boolean changesState(TimelineEvent e) {
        return switch (e.type()) {
            case "label", "assignees", "milestone", "change_title", "close", "reopen", "added_deadline",
                    "modified_deadline", "removed_deadline" -> true;
            default -> false;
        };
    }

    private static boolean isPullRequestRef(TimelineEvent e, List<PullRequest> pullRequests) {
        return e.refIssue() != null && e.refIssue().htmlUrl() != null
                && (e.type().equals("pull_ref") || e.type().equals("comment_ref"))
                && pullRequests.stream().anyMatch(p -> p.url().equals(e.refIssue().htmlUrl())
                        && p.at().equals(e.createdAt().toInstant()));
    }

    private static boolean sameAuthor(TimelineEvent a, TimelineEvent b) {
        return a.user() != null && b.user() != null && a.user().id() == b.user().id();
    }

    /** Changes {@code state} from after the event to before it. */
    private static void undo(TimelineEvent e, State state) {
        switch (e.type()) {
            case "label" -> {
                if (e.label() != null) {
                    if ("1".equals(e.body())) {
                        state.labels.remove(e.label().name());
                    } else {
                        state.labels.add(e.label().name());
                    }
                }
            }
            case "assignees" -> {
                if (e.assignee() != null) {
                    if (e.removedAssignee()) {
                        state.assignees.add(e.assignee());
                    } else {
                        state.assignees.removeIf(u -> u.id() == e.assignee().id());
                    }
                }
            }
            case "milestone" -> state.milestone = e.oldMilestone() == null || e.oldMilestone().title() == null
                    || e.oldMilestone().title().isEmpty() ? null : e.oldMilestone().title();
            case "change_title" -> {
                if (e.oldTitle() != null) {
                    state.title = e.oldTitle();
                }
            }
            case "close" -> state.closed = false;
            case "reopen" -> state.closed = true;
            case "added_deadline" -> state.due = null;
            case "removed_deadline" -> state.due = date(e.body(), 0);
            case "modified_deadline" -> state.due = date(e.body(), 1);
            default -> {
                // not about the issue's values
            }
        }
    }

    /** {@code new|old} for a modified deadline, a single date otherwise. */
    private static LocalDate date(String body, int part) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String[] parts = body.split("\\|");
        String value = parts[Math.min(part, parts.length - 1)].strip();
        try {
            return LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Changes diff(State before, State after, Mapping mapping) {
        Changes changes = new Changes();
        if (!Objects.equals(before.title, after.title)) {
            changes.record(Fields.SUMMARY, null, before.title, null, after.title);
        }
        Named typeBefore = mapping.issueType(before.labels);
        Named typeAfter = mapping.issueType(after.labels);
        changes.record(Fields.ISSUE_TYPE, typeBefore.id(), typeBefore.name(), typeAfter.id(), typeAfter.name());
        Named priorityBefore = mapping.priority(before.labels);
        Named priorityAfter = mapping.priority(after.labels);
        changes.record(Fields.PRIORITY, idOf(priorityBefore), nameOf(priorityBefore), idOf(priorityAfter),
                nameOf(priorityAfter));
        List<String> labelsBefore = labels(before, mapping);
        List<String> labelsAfter = labels(after, mapping);
        if (!labelsBefore.equals(labelsAfter)) {
            changes.record(Fields.LABELS, null, String.join(" ", labelsBefore), null, String.join(" ", labelsAfter));
        }
        Named assigneeBefore = before.assignees.isEmpty() ? null : mapping.user(before.assignees.getFirst());
        Named assigneeAfter = after.assignees.isEmpty() ? null : mapping.user(after.assignees.getFirst());
        changes.record(Fields.ASSIGNEE, idOf(assigneeBefore), nameOf(assigneeBefore), idOf(assigneeAfter),
                nameOf(assigneeAfter));
        if (!Objects.equals(before.milestone, after.milestone)) {
            if (before.milestone != null) {
                Named v = mapping.version(before.milestone);
                changes.record(Fields.FIX_VERSION, v.id(), v.name(), null, null);
            }
            if (after.milestone != null) {
                Named v = mapping.version(after.milestone);
                changes.record(Fields.FIX_VERSION, null, null, v.id(), v.name());
            }
        }
        changes.record(Fields.DUE_DATE, before.due, Objects.toString(before.due, null), after.due,
                Objects.toString(after.due, null));
        if (before.closed != after.closed) {
            Named from = mapping.status(before.closed, before.reopened);
            Named to = mapping.status(after.closed, after.reopened);
            changes.record(Fields.STATUS, from.id(), from.name(), to.id(), to.name());
            Named resolutionFrom = mapping.resolution(before.closed);
            Named resolutionTo = mapping.resolution(after.closed);
            changes.record(Fields.RESOLUTION, idOf(resolutionFrom), nameOf(resolutionFrom), idOf(resolutionTo),
                    nameOf(resolutionTo));
        }
        return changes;
    }

    private static List<String> labels(State state, Mapping mapping) {
        return state.labels.stream().map(mapping::label).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static Long idOf(Named named) {
        return named == null ? null : named.id();
    }

    private static String nameOf(Named named) {
        return named == null ? null : named.name();
    }
}
