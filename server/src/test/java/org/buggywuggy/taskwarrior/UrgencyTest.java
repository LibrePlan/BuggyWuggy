/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.taskwarrior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UrgencyTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final UrgencyProperties DEFAULTS = UrgencyProperties.defaults();

    private static Urgency.Facts facts(Instant created, LocalDate due, String priority, List<String> labels,
            int comments, int components, boolean active, boolean blocked, boolean blocking) {
        return new Urgency.Facts(1, created, due, false, active, priority, "SHOP", labels, comments, components,
                blocked, blocking);
    }

    @Test
    void calculatesTheExampleOfTaskWarrior() {
        // Two tags and a year old: tags 0.9 * 1 = 0.9, age 1 * 2 = 2, together 2.9.
        Urgency.Result result = Urgency.of(facts(NOW.minus(Duration.ofDays(400)), null, null,
                List.of("docs", "ui"), 0, 0, false, false, false), DEFAULTS, NOW, ZoneOffset.UTC);
        assertThat(result.terms()).containsExactly(new Urgency.Term("tags", 0.9, 1.0, 0.9),
                new Urgency.Term("age", 1.0, 2.0, 2.0));
        assertThat(result.urgency()).isCloseTo(2.9, within(1e-9));
    }

    @Test
    void usesEveryTermWithTaskWarriorsFormulas() {
        Urgency.Result result = Urgency.of(facts(NOW.minus(Duration.ofDays(73)), LocalDate.parse("2026-06-01"),
                "High", List.of("next", "waiting", "scheduled"), 2, 1, true, true, true), DEFAULTS, NOW,
                ZoneOffset.UTC);
        Map<String, Double> byName = result.terms().stream()
                .collect(java.util.stream.Collectors.toMap(Urgency.Term::name, Urgency.Term::urgency));
        assertThat(byName).containsOnlyKeys("project", "active", "scheduled", "waiting", "blocked", "annotations",
                "tags", "due", "age", "blocking", "priority High", "tag next");
        assertThat(byName.get("project")).isEqualTo(1.0);
        assertThat(byName.get("active")).isEqualTo(4.0);
        assertThat(byName.get("scheduled")).isEqualTo(5.0);
        assertThat(byName.get("waiting")).isEqualTo(-3.0);
        assertThat(byName.get("blocked")).isEqualTo(-5.0);
        assertThat(byName.get("annotations")).isCloseTo(0.9, within(1e-9));
        assertThat(byName.get("tags")).isEqualTo(1.0);
        // Due today at 0:00, now 12:00: half a day overdue.
        assertThat(byName.get("due")).isCloseTo(((0.5 + 14.0) * 0.8 / 21.0 + 0.2) * 12.0, within(1e-9));
        assertThat(byName.get("age")).isCloseTo(73.0 / 365 * 2.0, within(1e-9));
        assertThat(byName.get("blocking")).isEqualTo(8.0);
        assertThat(byName.get("priority High")).isEqualTo(6.0);
        assertThat(byName.get("tag next")).isEqualTo(15.0);
        assertThat(result.urgency()).isCloseTo(byName.values().stream().mapToDouble(d -> d).sum(), within(1e-9));
    }

    @Test
    void rampsTheDueDate() {
        assertThat(Urgency.due(null, NOW, ZoneOffset.UTC)).isZero();
        assertThat(Urgency.due(LocalDate.parse("2026-07-01"), NOW, ZoneOffset.UTC)).isEqualTo(0.2);
        assertThat(Urgency.due(LocalDate.parse("2026-05-01"), NOW, ZoneOffset.UTC)).isEqualTo(1.0);
        Instant midnight = LocalDate.parse("2026-06-15").atStartOfDay(ZoneOffset.UTC).toInstant();
        assertThat(Urgency.due(LocalDate.parse("2026-06-15"), midnight, ZoneOffset.UTC))
                .isCloseTo(14.0 * 0.8 / 21.0 + 0.2, within(1e-9));
        assertThat(Urgency.count(0)).isZero();
        assertThat(Urgency.count(1)).isEqualTo(0.8);
        assertThat(Urgency.count(2)).isEqualTo(0.9);
        assertThat(Urgency.count(5)).isEqualTo(1.0);
        assertThat(Urgency.age(NOW, NOW, 0)).isEqualTo(1.0);
    }

    @Test
    void resolvedIssuesHaveNoUrgencyAndConfiguredCoefficientsWin() {
        Urgency.Facts resolved = new Urgency.Facts(1, NOW, null, true, false, "High", "SHOP", List.of("next"), 0, 0,
                false, false);
        assertThat(Urgency.of(resolved, DEFAULTS, NOW, ZoneOffset.UTC).urgency()).isNull();

        UrgencyProperties configured = new UrgencyProperties(12.0, 8.0, 5.0, 4.0, 0.0, 1.0, 0.0, 1.0, -3.0, -5.0, 365,
                Map.of("HIGH", 2.5), Map.of("Next", 1.5), Map.of("shop", 4.0));
        Urgency.Result result = Urgency.of(facts(NOW, null, "High", List.of("next"), 0, 0, false, false, false),
                configured, NOW, ZoneOffset.UTC);
        assertThat(result.terms()).extracting(Urgency.Term::name, Urgency.Term::urgency).containsExactly(
                org.assertj.core.groups.Tuple.tuple("priority High", 2.5),
                org.assertj.core.groups.Tuple.tuple("project SHOP", 4.0),
                org.assertj.core.groups.Tuple.tuple("tag next", 1.5));
        // The defaults of the other priorities stay.
        assertThat(configured.priorityCoefficient("Medium")).isEqualTo(3.9);
    }
}
