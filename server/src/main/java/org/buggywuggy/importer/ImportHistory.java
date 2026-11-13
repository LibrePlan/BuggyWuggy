/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.buggywuggy.importer.ImportWriter.Named;
import org.buggywuggy.importer.ImportWriter.SourceUser;
import org.buggywuggy.importer.Source.CloseReason;
import org.buggywuggy.importer.Source.SourceEvent;
import org.buggywuggy.importer.Source.SourceEvent.Kind;
import org.buggywuggy.importer.Source.SourceIssue;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;

/**
 * Turns the events of an issue into BuggyWuggy history. Trackers record events ("label X
 * added"), BuggyWuggy records old and new values; so the importer starts at the issue as it
 * is now and walks the events backwards, undoing one at a time.
 *
 * <p>Events within a few seconds by the same person form one history entry, like one edit in
 * BuggyWuggy. Events when the issue was created describe its first state and are not history.
 */
public final class ImportHistory {

    private static final Duration SAME_EDIT = Duration.ofSeconds(3);

    /** How the importer maps the issue's values. */
    public interface Mapping {

        /** The BuggyWuggy label for a source label, or null when it maps to something else. */
        String label(String sourceLabel);

        Named issueType(Set<String> sourceLabels);

        /** May be null. */
        Named priority(Set<String> sourceLabels);

        Named user(SourceUser user);

        Named version(String milestone);

        Named status(boolean closed, boolean reopened);

        /** Null when not closed. */
        Named resolution(boolean closed, CloseReason reason);
    }

    /** One history entry. {@code author} is null for events without a user. */
    public record Entry(SourceUser author, Instant at, Changes changes) {
    }

    /**
     * A pull request that referenced the issue, or another web link ({@code relation} as in
     * {@code issue_remote_link}: PULL_REQUEST or OTHER).
     */
    public record PullRequest(String url, String title, SourceUser author, Instant at, String relation) {
    }

    public record Result(List<Entry> entries, List<PullRequest> pullRequests, boolean reopened) {
    }

    private static final class State {
        final Set<String> labels;
        final List<SourceUser> assignees;
        String milestone;
        String title;
        LocalDate due;
        boolean closed;
        CloseReason reason;
        boolean reopened;

        State(Set<String> labels, List<SourceUser> assignees, String milestone, String title, LocalDate due,
                boolean closed, CloseReason reason, boolean reopened) {
            this.labels = labels;
            this.assignees = assignees;
            this.milestone = milestone;
            this.title = title;
            this.due = due;
            this.closed = closed;
            this.reason = reason;
            this.reopened = reopened;
        }

        State copy() {
            return new State(new LinkedHashSet<>(labels), new ArrayList<>(assignees), milestone, title, due, closed,
                    reason, reopened);
        }
    }

    private ImportHistory() {
    }

    public static Result of(SourceIssue issue, List<SourceEvent> all, Mapping mapping) {
        List<SourceEvent> events = all.stream().filter(e -> e != null && e.at() != null && e.kind() != null)
                .sorted(Comparator.comparing(SourceEvent::at)).toList();
        boolean reopened = events.stream().anyMatch(e -> e.kind() == Kind.REOPENED);
        // Before a reopen, the issue was closed for the reason of the close before it.
        Map<SourceEvent, CloseReason> closedBefore = new IdentityHashMap<>();
        CloseReason lastClose = null;
        for (SourceEvent e : events) {
            if (e.kind() == Kind.CLOSED) {
                lastClose = e.closeReason() == null ? CloseReason.COMPLETED : e.closeReason();
            } else if (e.kind() == Kind.REOPENED) {
                closedBefore.put(e, lastClose == null ? CloseReason.COMPLETED : lastClose);
            }
        }

        List<PullRequest> pullRequests = new ArrayList<>();
        for (SourceEvent e : events) {
            if (isLink(e) && e.value() != null
                    && pullRequests.stream().noneMatch(p -> p.url().equals(e.value()))) {
                pullRequests.add(new PullRequest(e.value(), e.oldValue() == null ? e.value() : e.oldValue(),
                        e.author(), e.at(), e.kind() == Kind.PULL_REQUEST ? "PULL_REQUEST" : "OTHER"));
            }
        }

        List<List<SourceEvent>> groups = new ArrayList<>();
        for (SourceEvent e : events) {
            if (isLink(e) && pullRequests.stream().noneMatch(p -> p.url().equals(e.value())
                    && p.at().equals(e.at()))) {
                continue; // a later reference to a pull request that is already listed
            }
            List<SourceEvent> last = groups.isEmpty() ? null : groups.getLast();
            if (last != null && sameAuthor(last.getFirst(), e)
                    && Duration.between(last.getLast().at(), e.at()).compareTo(SAME_EDIT) <= 0) {
                last.add(e);
            } else {
                groups.add(new ArrayList<>(List.of(e)));
            }
        }

        State after = new State(new LinkedHashSet<>(issue.labels()), new ArrayList<>(issue.assignees()),
                issue.milestone(), issue.title(), issue.due(), issue.closed(), issue.closeReason(), reopened);
        Instant created = issue.createdAt();
        List<Entry> entries = new ArrayList<>();
        for (int g = groups.size() - 1; g >= 0; g--) {
            List<SourceEvent> group = groups.get(g);
            State before = after.copy();
            for (int i = group.size() - 1; i >= 0; i--) {
                undo(group.get(i), before, after, closedBefore);
            }
            Instant start = group.getFirst().at();
            before.reopened = events.stream().anyMatch(e -> e.kind() == Kind.REOPENED && e.at().isBefore(start));
            boolean atCreation = Duration.between(created, start).compareTo(SAME_EDIT) <= 0;
            if (!atCreation) {
                Changes changes = diff(before, after, mapping);
                for (SourceEvent e : group) {
                    if (isLink(e)) {
                        changes.record(Fields.REMOTE_LINK, null, null, e.value(),
                                e.oldValue() == null ? e.value() : e.oldValue());
                    }
                }
                if (!changes.isEmpty()) {
                    entries.add(new Entry(group.getFirst().author(), start, changes));
                }
            }
            after = before;
        }
        return new Result(entries.reversed(), pullRequests, reopened);
    }

    private static boolean sameAuthor(SourceEvent a, SourceEvent b) {
        return a.author() != null && b.author() != null && a.author().externalId().equals(b.author().externalId());
    }

    /** Changes {@code state} from after the event to before it. */
    private static void undo(SourceEvent e, State state, State after, Map<SourceEvent, CloseReason> closedBefore) {
        switch (e.kind()) {
            case LABEL_ADDED -> state.labels.remove(e.value());
            case LABEL_REMOVED -> state.labels.add(e.value());
            case ASSIGNED -> state.assignees.removeIf(u -> e.user() != null
                    && u.externalId().equals(e.user().externalId()));
            case UNASSIGNED -> {
                if (e.user() != null) {
                    state.assignees.add(e.user());
                }
            }
            case MILESTONE -> state.milestone = blankToNull(e.oldValue());
            case RENAMED -> {
                if (e.oldValue() != null) {
                    state.title = e.oldValue();
                }
            }
            case DUE_DATE -> state.due = date(e.oldValue());
            case CLOSED -> {
                // The state after the last close has the issue's reason; after an earlier one,
                // the reason the reopen found. Only when neither is known, the event's own.
                if (after.reason == null) {
                    after.reason = e.closeReason() == null ? CloseReason.COMPLETED : e.closeReason();
                }
                state.closed = false;
                state.reason = null;
            }
            case REOPENED -> {
                state.closed = true;
                state.reason = closedBefore.get(e);
            }
            case PULL_REQUEST, WEB_LINK -> {
                // not about the issue's values
            }
        }
    }

    private static boolean isLink(SourceEvent e) {
        return e.kind() == Kind.PULL_REQUEST || e.kind() == Kind.WEB_LINK;
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
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
            Named resolutionFrom = mapping.resolution(before.closed, before.reason);
            Named resolutionTo = mapping.resolution(after.closed, after.reason);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
