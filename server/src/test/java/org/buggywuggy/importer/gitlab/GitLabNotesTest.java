/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitLabNotesTest {

    @Test
    void readsTitleChangesWithTheirDiffMarkers() {
        assertThat(GitLabNotes.title("changed title from **Crash{- on start-}** to **Crash{+ at boot+}**"))
                .contains(new GitLabNotes.Title("Crash on start", "Crash at boot"));
        assertThat(GitLabNotes.title("changed title from **Old** to **New**"))
                .contains(new GitLabNotes.Title("Old", "New"));
        assertThat(GitLabNotes.title("changed the description")).isEmpty();
    }

    @Test
    void readsAssignments() {
        assertThat(GitLabNotes.assignment("assigned to @bob and unassigned @alice.smith"))
                .contains(new GitLabNotes.Assignment(List.of("bob"), List.of("alice.smith")));
        assertThat(GitLabNotes.assignment("assigned to @bob, @carol and @dave"))
                .contains(new GitLabNotes.Assignment(List.of("bob", "carol", "dave"), List.of()));
        assertThat(GitLabNotes.assignment("unassigned @bob"))
                .contains(new GitLabNotes.Assignment(List.of(), List.of("bob")));
        assertThat(GitLabNotes.assignment("reassigned to @carol"))
                .contains(new GitLabNotes.Assignment(List.of("carol"), List.of()));
        assertThat(GitLabNotes.assignment("mentioned in issue #3")).isEmpty();
    }

    @Test
    void readsDueDates() {
        assertThat(GitLabNotes.dueDate("changed due date to January 5, 2026"))
                .contains(Optional.of(LocalDate.of(2026, 1, 5)));
        assertThat(GitLabNotes.dueDate("removed due date")).contains(Optional.empty());
        assertThat(GitLabNotes.dueDate("changed milestone to %v1.0")).isEmpty();
    }
}
