/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.buggywuggy.importer.ImportWriter.Named;
import org.buggywuggy.importer.ImportWriter.SourceUser;
import org.buggywuggy.importer.Source.CloseReason;
import org.buggywuggy.importer.Source.SourceEvent;
import org.buggywuggy.importer.Source.SourceEvent.Kind;
import org.buggywuggy.importer.Source.SourceIssue;
import org.buggywuggy.issue.IssueHistory.Item;
import org.junit.jupiter.api.Test;

class ImportHistoryTest {

    private static final SourceUser ANN = new SourceUser("1", "ann", "Ann", null);
    private static final SourceUser BEN = new SourceUser("2", "ben", "Ben", null);

    private static final ImportHistory.Mapping MAPPING = new ImportHistory.Mapping() {
        public String label(String l) {
            return l;
        }

        public Named issueType(Set<String> labels) {
            return new Named(1, "Task");
        }

        public Named priority(Set<String> labels) {
            return null;
        }

        public Named user(SourceUser u) {
            return new Named(Long.parseLong(u.externalId()) * 10, u.name());
        }

        public Named version(String m) {
            return new Named(99, m);
        }

        public Named status(boolean closed, boolean reopened) {
            return closed ? new Named(5, "Closed") : reopened ? new Named(3, "Reopened") : new Named(1, "Open");
        }

        public Named resolution(boolean closed, CloseReason reason) {
            return !closed ? null : reason == CloseReason.NOT_PLANNED ? new Named(8, "Won't Fix")
                    : new Named(7, "Done");
        }
    };

    private static Instant at(int day) {
        return Instant.parse("2026-01-%02dT10:00:00Z".formatted(day));
    }

    private static SourceIssue issue(boolean closed, CloseReason reason, List<SourceUser> assignees) {
        return new SourceIssue("1", 1, "u", ANN, "Title", "", List.of(), null, assignees, closed, reason, at(1),
                at(9), null, null, 0, List.of());
    }

    private static SourceEvent event(Kind kind, SourceUser actor, int day, SourceUser user, CloseReason reason) {
        return new SourceEvent(kind, actor, at(day), null, null, user, reason);
    }

    @Test
    void walksBackFromTheCurrentStateThroughCloseReopenAndAssignments() {
        List<SourceEvent> events = List.of(
                event(Kind.ASSIGNED, ANN, 2, ANN, null),
                event(Kind.UNASSIGNED, ANN, 3, ANN, null),
                event(Kind.ASSIGNED, ANN, 3, BEN, null),
                event(Kind.CLOSED, BEN, 5, null, null),
                event(Kind.REOPENED, ANN, 7, null, null));

        ImportHistory.Result result = ImportHistory.of(issue(false, null, List.of(BEN)), events, MAPPING);

        assertThat(result.reopened()).isTrue();
        assertThat(result.entries()).hasSize(4);
        assertThat(result.entries().get(0).changes().items())
                .containsExactly(new Item("assignee", null, null, "10", "Ann"));
        // Removing Ann and adding Ben at the same moment is one change.
        assertThat(result.entries().get(1).changes().items())
                .containsExactly(new Item("assignee", "10", "Ann", "20", "Ben"));
        assertThat(result.entries().get(2).changes().items()).containsExactly(
                new Item("status", "1", "Open", "5", "Closed"), new Item("resolution", null, null, "7", "Done"));
        assertThat(result.entries().get(3).author()).isEqualTo(ANN);
        assertThat(result.entries().get(3).changes().items()).containsExactly(
                new Item("status", "5", "Closed", "3", "Reopened"), new Item("resolution", "7", "Done", null, null));
    }

    @Test
    void usesTheCloseReasonOfEachClose() {
        List<SourceEvent> events = List.of(
                event(Kind.CLOSED, BEN, 3, null, CloseReason.NOT_PLANNED),
                event(Kind.REOPENED, ANN, 5, null, null),
                event(Kind.CLOSED, ANN, 7, null, null));

        ImportHistory.Result result = ImportHistory.of(issue(true, CloseReason.COMPLETED, List.of()), events, MAPPING);

        assertThat(result.entries()).hasSize(3);
        assertThat(result.entries().get(0).changes().items())
                .contains(new Item("resolution", null, null, "8", "Won't Fix"));
        assertThat(result.entries().get(1).changes().items())
                .contains(new Item("resolution", "8", "Won't Fix", null, null));
        assertThat(result.entries().get(2).changes().items())
                .contains(new Item("resolution", null, null, "7", "Done"));
    }
}
