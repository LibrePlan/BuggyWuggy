/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.taskwarrior;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * TaskWarrior's urgency of an issue: a sum of terms, each a quality of the issue (the factor,
 * mostly 0 or 1) times its coefficient. The factors are TaskWarrior's own ({@code Task::urgency_*}
 * in TaskWarrior's source), with these meanings in BuggyWuggy:
 * <ul>
 * <li>project: the issue has a component; active: its status is in progress;</li>
 * <li>scheduled, waiting: it has the label {@code scheduled} or {@code waiting};</li>
 * <li>blocked, blocking: an unresolved issue blocks it, or it blocks one;</li>
 * <li>annotations: its comments; tags: its labels (0.8, 0.9 and 1.0 for one, two, more);</li>
 * <li>due: its due date, from 0.2 (two weeks or more ahead) to 1.0 (a week or more overdue);</li>
 * <li>age: its age in days divided by {@code age-max}, at most 1.0;</li>
 * <li>priority, and per label and project: their own coefficients, with factor 1.</li>
 * </ul>
 * Resolved issues have no urgency, as TaskWarrior's next report only lists pending tasks.
 */
public final class Urgency {

    /** What the urgency of one issue is calculated from. */
    public record Facts(long issueId, Instant createdAt, LocalDate dueDate, boolean resolved, boolean active,
            String priority, String projectKey, List<String> labels, int comments, int components, boolean blocked,
            boolean blocking) {
    }

    /** One line of the calculation: {@code factor * coefficient = urgency}. */
    public record Term(String name, double factor, double coefficient, double urgency) {
    }

    /** {@code urgency} null for a resolved issue; {@code terms} only those that count. */
    public record Result(Double urgency, List<Term> terms) {
    }

    private Urgency() {
    }

    public static Result of(Facts f, UrgencyProperties p, Instant now, ZoneId zone) {
        if (f.resolved()) {
            return new Result(null, List.of());
        }
        List<Term> terms = new ArrayList<>();
        boolean waiting = f.labels().stream().anyMatch(l -> l.equalsIgnoreCase("waiting"));
        boolean scheduled = f.labels().stream().anyMatch(l -> l.equalsIgnoreCase("scheduled"));
        add(terms, "project", f.components() > 0 ? 1.0 : 0.0, p.projectCoefficient());
        add(terms, "active", f.active() ? 1.0 : 0.0, p.activeCoefficient());
        add(terms, "scheduled", scheduled ? 1.0 : 0.0, p.scheduledCoefficient());
        add(terms, "waiting", waiting ? 1.0 : 0.0, p.waitingCoefficient());
        add(terms, "blocked", f.blocked() ? 1.0 : 0.0, p.blockedCoefficient());
        add(terms, "annotations", count(f.comments()), p.annotationsCoefficient());
        add(terms, "tags", count(f.labels().size()), p.tagsCoefficient());
        add(terms, "due", due(f.dueDate(), now, zone), p.dueCoefficient());
        add(terms, "age", age(f.createdAt(), now, p.ageMax()), p.ageCoefficient());
        add(terms, "blocking", f.blocking() ? 1.0 : 0.0, p.blockingCoefficient());
        if (f.priority() != null) {
            add(terms, "priority " + f.priority(), 1.0, p.priorityCoefficient(f.priority()));
        }
        Double project = f.projectKey() == null ? null : p.projectCoefficient(f.projectKey());
        if (project != null) {
            add(terms, "project " + f.projectKey(), 1.0, project);
        }
        for (String label : f.labels()) {
            Double tag = p.tagCoefficient(label);
            if (tag != null) {
                add(terms, "tag " + label, 1.0, tag);
            }
        }
        double total = terms.stream().mapToDouble(Term::urgency).sum();
        return new Result(total, List.copyOf(terms));
    }

    private static void add(List<Term> terms, String name, double factor, double coefficient) {
        double urgency = factor * coefficient;
        if (urgency != 0) {
            terms.add(new Term(name, factor, coefficient, urgency));
        }
    }

    /** Tags and annotations: 0.8 for one, 0.9 for two, 1.0 for three or more. */
    static double count(int n) {
        return n >= 3 ? 1.0 : n == 2 ? 0.9 : n == 1 ? 0.8 : 0.0;
    }

    /**
     * A range of 21 days mapped to 0.2–1.0: 1.0 from a week overdue, 0.2 from two weeks
     * ahead. A due date counts from the start of that day.
     */
    static double due(LocalDate due, Instant now, ZoneId zone) {
        if (due == null) {
            return 0.0;
        }
        double daysOverdue = Duration.between(due.atStartOfDay(zone).toInstant(), now).toMillis() / 86_400_000.0;
        if (daysOverdue >= 7.0) {
            return 1.0;
        }
        if (daysOverdue >= -14.0) {
            return (daysOverdue + 14.0) * 0.8 / 21.0 + 0.2;
        }
        return 0.2;
    }

    /** The age in days divided by the maximum age, at most 1.0 (1.0 when the maximum is 0). */
    static double age(Instant created, Instant now, int max) {
        if (created == null) {
            return 0.0;
        }
        double days = Duration.between(created, now).toMillis() / 86_400_000.0;
        if (max <= 0 || days > max) {
            return 1.0;
        }
        return Math.max(0.0, days / max);
    }
}
