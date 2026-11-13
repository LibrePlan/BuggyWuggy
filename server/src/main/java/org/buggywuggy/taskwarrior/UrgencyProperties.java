/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.taskwarrior;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code buggywuggy.taskwarrior.urgency.*} in the configuration file: the coefficients of
 * TaskWarrior's urgency (https://taskwarrior.org/docs/urgency/), with TaskWarrior's defaults.
 * The maps add to and override their defaults per key; keys are matched without regard to case.
 *
 * @param priority  {@code urgency.uda.priority.<name>.coefficient}, by priority name
 * @param tags      {@code urgency.user.tag.<label>.coefficient}, by label
 * @param projects  {@code urgency.user.project.<key>.coefficient}, by project key
 * @param ageMax    {@code urgency.age.max}: the age in days at which the age term is 1.0
 */
@ConfigurationProperties("buggywuggy.taskwarrior.urgency")
public record UrgencyProperties(
        @DefaultValue("12.0") double dueCoefficient,
        @DefaultValue("8.0") double blockingCoefficient,
        @DefaultValue("5.0") double scheduledCoefficient,
        @DefaultValue("4.0") double activeCoefficient,
        @DefaultValue("2.0") double ageCoefficient,
        @DefaultValue("1.0") double annotationsCoefficient,
        @DefaultValue("1.0") double tagsCoefficient,
        @DefaultValue("1.0") double projectCoefficient,
        @DefaultValue("-3.0") double waitingCoefficient,
        @DefaultValue("-5.0") double blockedCoefficient,
        @DefaultValue("365") int ageMax,
        Map<String, Double> priority,
        Map<String, Double> tags,
        Map<String, Double> projects) {

    /** The priorities of a new BuggyWuggy, after TaskWarrior's H 6.0, M 3.9 and L 1.8. */
    public static final Map<String, Double> DEFAULT_PRIORITY = defaults("Highest", 8.0, "High", 6.0, "Medium", 3.9,
            "Low", 1.8, "Lowest", 0.9);
    /** TaskWarrior's urgency.user.tag.next.coefficient. */
    public static final Map<String, Double> DEFAULT_TAGS = defaults("next", 15.0);

    public UrgencyProperties {
        priority = merged(DEFAULT_PRIORITY, priority);
        tags = merged(DEFAULT_TAGS, tags);
        projects = merged(Map.of(), projects);
    }

    /** All defaults, as when nothing is configured. */
    public static UrgencyProperties defaults() {
        return new UrgencyProperties(12.0, 8.0, 5.0, 4.0, 2.0, 1.0, 1.0, 1.0, -3.0, -5.0, 365, null, null, null);
    }

    public double priorityCoefficient(String name) {
        return name == null ? 0 : priority.getOrDefault(name.toLowerCase(Locale.ROOT), 0.0);
    }

    public Double tagCoefficient(String label) {
        return tags.get(label.toLowerCase(Locale.ROOT));
    }

    public Double projectCoefficient(String key) {
        return projects.get(key.toLowerCase(Locale.ROOT));
    }

    /** Keys in lower case, configured values over the defaults. */
    private static Map<String, Double> merged(Map<String, Double> defaults, Map<String, Double> configured) {
        Map<String, Double> map = new TreeMap<>();
        defaults.forEach((k, v) -> map.put(k.toLowerCase(Locale.ROOT), v));
        if (configured != null) {
            configured.forEach((k, v) -> {
                if (k != null && v != null) {
                    map.put(k.strip().toLowerCase(Locale.ROOT), v);
                }
            });
        }
        return Map.copyOf(map);
    }

    private static Map<String, Double> defaults(Object... keysAndValues) {
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], (Double) keysAndValues[i + 1]);
        }
        return map;
    }
}
