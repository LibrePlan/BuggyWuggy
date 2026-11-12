/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.notification;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A user's mail settings, stored in {@code user_preference}. */
@Component
public class NotificationPreferences {

    static final String NOTIFICATIONS = "mail.notifications";
    static final String OWN_CHANGES = "mail.own-changes";

    /** @param notifications mails about issues at all (default on)
     *  @param ownChanges also mails about one's own changes (default off) */
    public record Preferences(boolean notifications, boolean ownChanges) {
    }

    private final JdbcTemplate jdbc;

    public NotificationPreferences(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Preferences of(long userId) {
        Map<String, String> values = new java.util.HashMap<>();
        jdbc.query("select key, value from user_preference where user_id = ? and key in (?, ?)",
                rs -> {
                    values.put(rs.getString(1), rs.getString(2));
                }, userId, NOTIFICATIONS, OWN_CHANGES);
        return new Preferences(!"false".equals(values.get(NOTIFICATIONS)), "true".equals(values.get(OWN_CHANGES)));
    }

    public void set(long userId, Boolean notifications, Boolean ownChanges) {
        if (notifications != null) {
            put(userId, NOTIFICATIONS, notifications);
        }
        if (ownChanges != null) {
            put(userId, OWN_CHANGES, ownChanges);
        }
    }

    private void put(long userId, String key, boolean value) {
        jdbc.update("""
                insert into user_preference (user_id, key, value) values (?, ?, ?)
                on conflict (user_id, key) do update set value = excluded.value""", userId, key, String.valueOf(value));
    }
}
