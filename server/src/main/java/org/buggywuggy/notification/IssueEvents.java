/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.buggywuggy.issue.IssueHistory.Item;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.project.ProjectPermissionService;
import org.buggywuggy.webhook.WebhookService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records what happened to issues in the transactional outbox ({@code outbox_event}), in the
 * same transaction as the change itself: a change that is rolled back sends no mail, and a
 * restart loses none. {@link NotificationDispatcher} turns the events into mails.
 */
@Component
public class IssueEvents {

    public static final String CREATED = "issue.created";
    public static final String CHANGED = "issue.changed";
    public static final String COMMENTED = "issue.commented";
    public static final String DELETED = "issue.deleted";

    /** Payload of {@link #CHANGED}: the change items as stored in the history. */
    public record Changed(List<Item> items) {
    }

    public record Commented(long commentId) {
    }

    /** Payload of {@link #DELETED}: what the mail needs, since the issue is gone when it is sent. */
    public record Deleted(String key, String summary, String issueType, String status, String projectKey,
            String projectName, List<Long> recipients) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ProjectPermissionService permissions;
    private final JsonMapper json;
    private final WebhookService webhooks;

    public IssueEvents(NamedParameterJdbcTemplate jdbc, ProjectPermissionService permissions, JsonMapper json,
            WebhookService webhooks) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.json = json;
        this.webhooks = webhooks;
    }

    public void created(long issueId, Long actorId, Instant at) {
        record(CREATED, issueId, actorId, at, Map.of());
    }

    public void changed(long issueId, Long actorId, Instant at, List<Item> items) {
        if (!items.isEmpty()) {
            record(CHANGED, issueId, actorId, at, new Changed(items));
        }
    }

    public void commented(long issueId, Long actorId, Instant at, long commentId) {
        record(COMMENTED, issueId, actorId, at, new Commented(commentId));
    }

    /** Call before the issue is deleted: who to tell is taken from the issue as it is now. */
    public void deleted(long issueId, Long actorId, Instant at) {
        Map<String, Object> params = Map.of("id", issueId);
        record Row(long projectId, Deleted deleted) {
        }
        Row row = jdbc.queryForObject("""
                select p.id, p.key || '-' || i.number, i.summary, t.name, s.name, p.key, p.name
                from issue i join project p on p.id = i.project_id
                join issue_type t on t.id = i.issue_type_id join status s on s.id = i.status_id
                where i.id = :id""", params, (rs, n) -> new Row(rs.getLong(1), new Deleted(rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), List.of())));
        Set<Long> recipients = new LinkedHashSet<>(jdbc.queryForList("""
                select reporter_id from issue where id = :id and reporter_id is not null
                union select assignee_id from issue where id = :id and assignee_id is not null
                union select user_id from watcher where issue_id = :id""", params, Long.class));
        List<Long> allowed = recipients.stream()
                .filter(u -> permissions.userHas(u, row.projectId(), ProjectPermission.BROWSE_PROJECT))
                .toList();
        Deleted d = row.deleted();
        record(DELETED, issueId, actorId, at, new Deleted(d.key(), d.summary(), d.issueType(), d.status(),
                d.projectKey(), d.projectName(), allowed));
    }

    private void record(String type, long issueId, Long actorId, Instant at, Object payload) {
        jdbc.update("""
                insert into outbox_event (event_type, payload, created_at, issue_id, actor_id)
                values (:type, cast(:payload as jsonb), :at, :issue, :actor)""",
                new MapSqlParameterSource().addValue("type", type)
                        .addValue("payload", json.writeValueAsString(payload))
                        .addValue("at", Timestamp.from(at)).addValue("issue", issueId).addValue("actor", actorId));
        webhooks.enqueue(type, issueId, actorId, at, payload instanceof Changed c ? c.items() : null,
                payload instanceof Commented cm ? cm.commentId() : null);
    }
}
