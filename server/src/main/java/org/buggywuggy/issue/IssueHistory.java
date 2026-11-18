/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import org.buggywuggy.common.ApiNullable;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.buggywuggy.notification.IssueEvents;
import org.buggywuggy.user.UserDtos.UserRef;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Append-only change history of issues ({@code change_group} / {@code change_item}).
 * Each item has a machine value (e.g. an id) and a human-readable text as it was at the time.
 * Every saved change is also recorded as an {@link IssueEvents#CHANGED} event for notifications.
 */
@Component
public class IssueHistory {

    /** Field keys used in change items. */
    public static final class Fields {
        public static final String SUMMARY = "summary";
        public static final String DESCRIPTION = "description";
        public static final String ISSUE_TYPE = "issuetype";
        public static final String PRIORITY = "priority";
        public static final String ASSIGNEE = "assignee";
        public static final String REPORTER = "reporter";
        public static final String DUE_DATE = "duedate";
        public static final String LABELS = "labels";
        public static final String COMPONENT = "component";
        public static final String AFFECTS_VERSION = "version";
        public static final String FIX_VERSION = "fixversion";
        public static final String LINK = "link";
        public static final String ATTACHMENT = "attachment";
        public static final String PARENT = "parent";
        public static final String STATUS = "status";
        public static final String RESOLUTION = "resolution";
        /** The order on the board: value = "higher" or "lower", text = e.g. "Ranked higher". */
        public static final String RANK = "rank";
        /** Where an imported issue came from: value = original URL, text = e.g. "Forgejo owner/repo#12". */
        public static final String IMPORT = "import";
        /** A link to something outside BuggyWuggy, e.g. a pull request: value = URL, text = title. */
        public static final String REMOTE_LINK = "remotelink";
        /** The project, when the issue moved to another one: value = project id, text = its name. */
        public static final String PROJECT = "project";
        /** The key, when the issue moved to another project: text only, e.g. "BUG-12" → "SUP-48". */
        public static final String KEY = "key";

        private Fields() {
        }
    }

    public record Item(String field, @ApiNullable String from, @ApiNullable String fromText, @ApiNullable String to,
            @ApiNullable String toText) {
    }

    public record Entry(long id, @ApiNullable UserRef author, Instant createdAt, List<Item> items) {
    }

    /** Collects the changes of one update. Items that do not change anything are ignored. */
    public static final class Changes {

        private final List<Item> items = new ArrayList<>();

        public void record(String field, Object from, String fromText, Object to, String toText) {
            String fromValue = from == null ? null : from.toString();
            String toValue = to == null ? null : to.toString();
            if (!Objects.equals(fromValue, toValue) || !Objects.equals(fromText, toText)) {
                items.add(new Item(field, fromValue, fromText, toValue, toText));
            }
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public List<Item> items() {
            return List.copyOf(items);
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final IssueEvents events;

    public IssueHistory(NamedParameterJdbcTemplate jdbc, IssueEvents events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    public void save(long issueId, Long authorId, Instant at, Changes changes) {
        if (changes.isEmpty()) {
            return;
        }
        record(issueId, authorId, at, changes);
        events.changed(issueId, authorId, at, changes.items());
    }

    /** Writes history without notifying anyone, e.g. history taken over by an importer. */
    public void record(long issueId, Long authorId, Instant at, Changes changes) {
        if (changes.isEmpty()) {
            return;
        }
        Long groupId = jdbc.queryForObject("""
                insert into change_group (issue_id, author_id, created_at)
                values (:issue, :author, :at) returning id""",
                new MapSqlParameterSource().addValue("issue", issueId).addValue("author", authorId)
                        .addValue("at", Timestamp.from(at)), Long.class);
        MapSqlParameterSource[] batch = changes.items().stream()
                .map(i -> new MapSqlParameterSource()
                        .addValue("group", groupId).addValue("field", i.field())
                        .addValue("from", i.from()).addValue("fromText", i.fromText())
                        .addValue("to", i.to()).addValue("toText", i.toText()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into change_item (change_group_id, field_key, old_value, old_text, new_value, new_text)
                values (:group, :field, :from, :fromText, :to, :toText)""", batch);
    }

    /** The history of an issue, oldest first. */
    public List<Entry> list(long issueId) {
        Map<Long, Entry> entries = new LinkedHashMap<>();
        jdbc.query("""
                select g.id, g.created_at, u.id as user_id, u.username, u.display_name,
                       ci.field_key, ci.old_value, ci.old_text, ci.new_value, ci.new_text
                from change_group g
                left join user_account u on u.id = g.author_id
                join change_item ci on ci.change_group_id = g.id
                where g.issue_id = :issue
                order by g.created_at, g.id, ci.id""", Map.of("issue", issueId), rs -> {
            long groupId = rs.getLong("id");
            Entry entry = entries.computeIfAbsent(groupId, id -> {
                try {
                    long userId = rs.getLong("user_id");
                    UserRef author = rs.wasNull() ? null
                            : new UserRef(userId, rs.getString("username"), rs.getString("display_name"));
                    return new Entry(id, author, rs.getTimestamp("created_at").toInstant(), new ArrayList<>());
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            entry.items().add(new Item(rs.getString("field_key"), rs.getString("old_value"),
                    rs.getString("old_text"), rs.getString("new_value"), rs.getString("new_text")));
        });
        return List.copyOf(entries.values());
    }
}
