/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.forgejo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.buggywuggy.importer.ImportWriter.Named;
import org.buggywuggy.importer.forgejo.ForgejoApi.Issue;
import org.buggywuggy.importer.forgejo.ForgejoApi.TimelineEvent;
import org.buggywuggy.importer.forgejo.ForgejoApi.User;
import org.buggywuggy.issue.IssueHistory.Item;
import org.junit.jupiter.api.Test;

class ForgejoHistoryTest {

    private static final User ANN = new User(1, "ann", "Ann", null, false);
    private static final User BEN = new User(2, "ben", "Ben", null, false);

    private static final ForgejoHistory.Mapping MAPPING = new ForgejoHistory.Mapping() {
        public String label(String l) {
            return l;
        }

        public Named issueType(Set<String> labels) {
            return new Named(1, "Task");
        }

        public Named priority(Set<String> labels) {
            return null;
        }

        public Named user(User u) {
            return new Named(u.id() * 10, u.fullName());
        }

        public Named version(String m) {
            return new Named(99, m);
        }

        public Named status(boolean closed, boolean reopened) {
            return closed ? new Named(5, "Closed") : reopened ? new Named(3, "Reopened") : new Named(1, "Open");
        }

        public Named resolution(boolean closed) {
            return closed ? new Named(7, "Done") : null;
        }
    };

    private static OffsetDateTime at(int day) {
        return OffsetDateTime.parse("2026-01-%02dT10:00:00Z".formatted(day));
    }

    private static TimelineEvent event(long id, String type, User user, int day, User assignee, boolean removed) {
        return new TimelineEvent(id, type, user, "", at(day), null, assignee, removed, null, null, null, null, null,
                null, null);
    }

    @Test
    void walksBackFromTheCurrentStateThroughCloseReopenAndAssignments() {
        Issue issue = new Issue(1, 1, "u", ANN, "", "Title", "", List.of(), List.of(), null, List.of(BEN), "open", 0,
                at(1), at(9), null, null, null, null);
        List<TimelineEvent> timeline = List.of(
                event(1, "assignees", ANN, 2, ANN, false),
                event(2, "assignees", ANN, 3, ANN, true),
                event(3, "assignees", ANN, 3, BEN, false),
                event(4, "close", BEN, 5, null, false),
                event(5, "reopen", ANN, 7, null, false));

        ForgejoHistory.Result result = ForgejoHistory.of(issue, timeline, MAPPING);

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
}
