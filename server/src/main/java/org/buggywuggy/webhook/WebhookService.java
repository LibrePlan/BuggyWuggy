/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.webhook;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.notFound;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.issue.IssueHistory.Item;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outgoing webhooks, managed by administrators: what happens to issues is posted as JSON
 * (format {@value #FORMAT}) to a URL, signed with a secret. {@link #enqueue} is called in the
 * transaction of the change itself, so a change that is rolled back posts nothing and a restart
 * loses nothing; {@link WebhookSender} posts the deliveries, in order per webhook, with retries.
 */
@Service
public class WebhookService {

    public static final String FORMAT = "buggywuggy-webhook/1";
    public static final String PING = "ping";
    /** The events a webhook can subscribe to; the same names as in IssueEvents. */
    public static final List<String> EVENTS = List.of("issue.created", "issue.changed", "issue.commented",
            "issue.deleted");
    static final String SECRET_PREFIX = "bwwh_";

    public record WebhookRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 2000) String url,
            @NotEmpty List<String> events, boolean allProjects, @ApiNullable List<String> projectKeys,
            boolean active) {
    }

    public record LastDelivery(String status, Instant at, @ApiNullable Integer responseStatus) {
    }

    public record WebhookResponse(long id, String name, String url, List<String> events, boolean allProjects,
            List<String> projectKeys, boolean active, Instant createdAt, Instant updatedAt, long pending,
            long failed, @ApiNullable LastDelivery lastDelivery) {
    }

    /** A new webhook or a new secret: the secret is shown only this once. */
    public record WebhookSecretResponse(WebhookResponse webhook, String secret) {
    }

    public record DeliveryResponse(long id, String event, String status, int attempts, Instant createdAt,
            @ApiNullable Instant sentAt, @ApiNullable Instant nextAttemptAt, @ApiNullable Integer responseStatus,
            @ApiNullable String lastError, String body) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final WebhookKey key;
    private final JsonMapper json;
    private final Clock clock;
    private final String baseUrl;
    private final SecureRandom random = new SecureRandom();

    public WebhookService(NamedParameterJdbcTemplate jdbc, WebhookKey key, JsonMapper json, Clock clock,
            @Value("${buggywuggy.base-url}") String baseUrl) {
        this.jdbc = jdbc;
        this.key = key;
        this.json = json;
        this.clock = clock;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    // --- administration -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<WebhookResponse> list() {
        return jdbc.query(SELECT + " order by w.name, w.id", Map.of(), this::webhook);
    }

    @Transactional(readOnly = true)
    public WebhookResponse get(long id) {
        return jdbc.query(SELECT + " where w.id = :id", Map.of("id", id), this::webhook).stream().findFirst()
                .orElseThrow(() -> notFound("webhook.not-found"));
    }

    @Transactional
    public WebhookSecretResponse create(WebhookRequest request) {
        List<Long> projects = validate(request);
        Instant now = clock.instant();
        Long id = jdbc.queryForObject("""
                insert into webhook (name, url, events, all_projects, active, secret_encrypted, created_at, updated_at)
                values (:name, :url, :events, :all, :active, '\\x'::bytea, :now, :now) returning id""",
                params(request).addValue("now", Timestamp.from(now)), Long.class);
        setProjects(id, projects);
        String secret = newSecret(id);
        return new WebhookSecretResponse(get(id), secret);
    }

    @Transactional
    public WebhookResponse update(long id, WebhookRequest request) {
        get(id);
        List<Long> projects = validate(request);
        jdbc.update("""
                update webhook set name = :name, url = :url, events = :events, all_projects = :all,
                                   active = :active, updated_at = :now
                where id = :id""", params(request).addValue("id", id).addValue("now", Timestamp.from(clock.instant())));
        setProjects(id, projects);
        return get(id);
    }

    /** A new signing secret; the old one stops working right away. */
    @Transactional
    public WebhookSecretResponse rotateSecret(long id) {
        get(id);
        String secret = newSecret(id);
        return new WebhookSecretResponse(get(id), secret);
    }

    @Transactional
    public void delete(long id) {
        if (jdbc.update("delete from webhook where id = :id", Map.of("id", id)) == 0) {
            throw notFound("webhook.not-found");
        }
    }

    /** The last 50 deliveries, newest first. */
    @Transactional(readOnly = true)
    public List<DeliveryResponse> deliveries(long id) {
        get(id);
        return jdbc.query("""
                select * from webhook_delivery where webhook_id = :id order by id desc limit 50""",
                Map.of("id", id), DELIVERY);
    }

    /** Posts a "ping" event, to check the URL and the secret. */
    @Transactional
    public DeliveryResponse test(long id) {
        WebhookResponse webhook = get(id);
        Map<String, Object> body = envelope(PING, clock.instant());
        body.put("webhook", Map.of("id", webhook.id(), "name", webhook.name()));
        long delivery = insertDelivery(id, PING, json.writeValueAsString(body), clock.instant());
        return delivery(id, delivery);
    }

    /** Tries a delivery that failed for good once more, with a fresh number of attempts. */
    @Transactional
    public DeliveryResponse retry(long id, long deliveryId) {
        int updated = jdbc.update("""
                update webhook_delivery set status = 'PENDING', attempts = 0, next_attempt_at = :now, last_error = null
                where id = :delivery and webhook_id = :id and status = 'FAILED'""",
                new MapSqlParameterSource("id", id).addValue("delivery", deliveryId)
                        .addValue("now", Timestamp.from(clock.instant())));
        if (updated == 0) {
            throw notFound("webhook.delivery-not-found");
        }
        return delivery(id, deliveryId);
    }

    /** The signing secret of a webhook, for {@link WebhookSender}. */
    String secret(long id, byte[] encrypted) {
        return new String(key.decrypt(encrypted, context(id)), StandardCharsets.UTF_8);
    }

    private DeliveryResponse delivery(long id, long deliveryId) {
        return jdbc.query("select * from webhook_delivery where id = :delivery and webhook_id = :id",
                Map.of("id", id, "delivery", deliveryId), DELIVERY).stream().findFirst()
                .orElseThrow(() -> notFound("webhook.delivery-not-found"));
    }

    private static String context(long id) {
        return "webhook:" + id;
    }

    private String newSecret(long id) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String secret = SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("update webhook set secret_encrypted = :secret, updated_at = :now where id = :id",
                new MapSqlParameterSource("id", id)
                        .addValue("secret", key.encrypt(secret.getBytes(StandardCharsets.UTF_8), context(id)))
                        .addValue("now", Timestamp.from(clock.instant())));
        return secret;
    }

    private List<Long> validate(WebhookRequest request) {
        URI uri;
        try {
            uri = new URI(request.url().strip());
        } catch (URISyntaxException e) {
            throw badRequest("webhook.bad-url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw badRequest("webhook.bad-url");
        }
        for (String event : request.events()) {
            if (!EVENTS.contains(event)) {
                throw badRequest("webhook.bad-event", event);
            }
        }
        if (request.allProjects()) {
            return List.of();
        }
        List<String> keys = request.projectKeys() == null ? List.of() : request.projectKeys();
        if (keys.isEmpty()) {
            throw badRequest("webhook.no-projects");
        }
        List<Long> ids = jdbc.queryForList("select id from project where key in (:keys)", Map.of("keys", keys),
                Long.class);
        if (ids.size() != Set.copyOf(keys).size()) {
            throw badRequest("webhook.unknown-project");
        }
        return ids;
    }

    private static MapSqlParameterSource params(WebhookRequest request) {
        return new MapSqlParameterSource().addValue("name", request.name().strip())
                .addValue("url", request.url().strip())
                .addValue("events", EVENTS.stream().filter(request.events()::contains).toArray(String[]::new))
                .addValue("all", request.allProjects()).addValue("active", request.active());
    }

    private void setProjects(long id, List<Long> projects) {
        jdbc.update("delete from webhook_project where webhook_id = :id", Map.of("id", id));
        for (Long project : projects) {
            jdbc.update("insert into webhook_project (webhook_id, project_id) values (:id, :project)",
                    Map.of("id", id, "project", project));
        }
    }

    private static final String SELECT = """
            select w.*,
                   array(select p.key from webhook_project wp join project p on p.id = wp.project_id
                         where wp.webhook_id = w.id order by p.key) as project_keys,
                   (select count(*) from webhook_delivery d where d.webhook_id = w.id and d.status = 'PENDING') as pending,
                   (select count(*) from webhook_delivery d where d.webhook_id = w.id and d.status = 'FAILED') as failed,
                   l.status as last_status, coalesce(l.sent_at, l.created_at) as last_at,
                   l.response_status as last_response
            from webhook w
            left join lateral (select * from webhook_delivery d where d.webhook_id = w.id and d.status <> 'PENDING'
                               order by d.id desc limit 1) l on true""";

    private WebhookResponse webhook(java.sql.ResultSet rs, int n) throws SQLException {
        LastDelivery last = rs.getString("last_status") == null ? null
                : new LastDelivery(rs.getString("last_status"), rs.getTimestamp("last_at").toInstant(),
                        (Integer) rs.getObject("last_response"));
        return new WebhookResponse(rs.getLong("id"), rs.getString("name"), rs.getString("url"),
                strings(rs.getArray("events")), rs.getBoolean("all_projects"), strings(rs.getArray("project_keys")),
                rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getLong("pending"), rs.getLong("failed"), last);
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static final RowMapper<DeliveryResponse> DELIVERY = (rs, n) -> new DeliveryResponse(rs.getLong("id"),
            rs.getString("event_type"), rs.getString("status"), rs.getInt("attempts"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
            "PENDING".equals(rs.getString("status")) ? rs.getTimestamp("next_attempt_at").toInstant() : null,
            (Integer) rs.getObject("response_status"), rs.getString("last_error"), rs.getString("body"));

    // --- events ---------------------------------------------------------------------------

    /**
     * Queues the event for every active webhook that wants it and covers the issue's project.
     * Called by IssueEvents in the transaction of the change. Events are often recorded before
     * the change itself is written, so the issue is read just before the commit, as it is then;
     * a deleted issue is read right away, while it still exists. {@code changes}: the changed
     * fields (issue.changed); {@code commentId}: issue.commented.
     */
    public void enqueue(String event, long issueId, Long actorId, Instant at, List<Item> changes, Long commentId) {
        if (!"issue.deleted".equals(event) && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    enqueueNow(event, issueId, actorId, at, changes, commentId);
                }
            });
        } else {
            enqueueNow(event, issueId, actorId, at, changes, commentId);
        }
    }

    private void enqueueNow(String event, long issueId, Long actorId, Instant at, List<Item> changes,
            Long commentId) {
        if (jdbc.queryForList("select 1 from issue where id = :id", Map.of("id", issueId)).isEmpty()) {
            return; // Deleted later in the same transaction: its own event says so.
        }
        List<Long> webhooks = jdbc.queryForList("""
                select w.id from webhook w
                where w.active and :event = any (w.events)
                  and (w.all_projects or exists (
                      select 1 from webhook_project wp join issue i on i.project_id = wp.project_id
                      where wp.webhook_id = w.id and i.id = :issue))
                order by w.id""", Map.of("event", event, "issue", issueId), Long.class);
        if (webhooks.isEmpty()) {
            return;
        }
        Map<String, Object> body = envelope(event, at);
        body.put("actor", actorId == null ? null : user(actorId));
        body.put("issue", issue(issueId));
        if (changes != null) {
            body.put("changes", changes);
        }
        if (commentId != null) {
            body.put("comment", comment(commentId));
        }
        String text = json.writeValueAsString(body);
        for (Long webhook : webhooks) {
            insertDelivery(webhook, event, text, at);
        }
    }

    private Map<String, Object> envelope(String event, Instant at) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("format", FORMAT);
        body.put("eventId", UUID.randomUUID().toString());
        body.put("event", event);
        body.put("occurredAt", at.truncatedTo(ChronoUnit.SECONDS).toString());
        body.put("installation", baseUrl);
        return body;
    }

    private long insertDelivery(long webhook, String event, String body, Instant at) {
        return jdbc.queryForObject("""
                insert into webhook_delivery (webhook_id, event_type, body, next_attempt_at, created_at)
                values (:webhook, :event, :body, :now, :now) returning id""",
                new MapSqlParameterSource("webhook", webhook).addValue("event", event).addValue("body", body)
                        .addValue("now", Timestamp.from(at)), Long.class);
    }

    private Map<String, Object> user(long id) {
        return jdbc.query("select id, username, display_name from user_account where id = :id", Map.of("id", id),
                (rs, n) -> userMap(rs.getLong(1), rs.getString(2), rs.getString(3))).stream().findFirst().orElse(null);
    }

    private static Map<String, Object> userMap(Long id, String username, String displayName) {
        if (id == null) {
            return null;
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id);
        user.put("username", username);
        user.put("displayName", displayName);
        return user;
    }

    private Map<String, Object> issue(long id) {
        return jdbc.queryForObject("""
                select i.id, p.key || '-' || i.number as key, i.summary, i.description, t.name as type,
                       s.name as status, pr.name as priority, p.key as project_key, p.name as project_name,
                       r.id as r_id, r.username as r_username, r.display_name as r_name,
                       a.id as a_id, a.username as a_username, a.display_name as a_name,
                       i.due_date, i.created_at, i.updated_at, i.resolved_at
                from issue i join project p on p.id = i.project_id
                join issue_type t on t.id = i.issue_type_id join status s on s.id = i.status_id
                left join priority pr on pr.id = i.priority_id
                left join user_account r on r.id = i.reporter_id
                left join user_account a on a.id = i.assignee_id
                where i.id = :id""", Map.of("id", id), (rs, n) -> {
                    Map<String, Object> issue = new LinkedHashMap<>();
                    issue.put("id", rs.getLong("id"));
                    issue.put("key", rs.getString("key"));
                    issue.put("url", baseUrl + "/browse/" + rs.getString("key"));
                    issue.put("summary", rs.getString("summary"));
                    issue.put("description", rs.getString("description"));
                    issue.put("type", rs.getString("type"));
                    issue.put("status", rs.getString("status"));
                    issue.put("priority", rs.getString("priority"));
                    issue.put("project", Map.of("key", rs.getString("project_key"), "name",
                            rs.getString("project_name")));
                    issue.put("reporter", userMap((Long) rs.getObject("r_id"), rs.getString("r_username"),
                            rs.getString("r_name")));
                    issue.put("assignee", userMap((Long) rs.getObject("a_id"), rs.getString("a_username"),
                            rs.getString("a_name")));
                    LocalDate due = rs.getObject("due_date", LocalDate.class);
                    issue.put("dueDate", due == null ? null : due.toString());
                    issue.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    issue.put("updatedAt", rs.getTimestamp("updated_at").toInstant().toString());
                    Timestamp resolved = rs.getTimestamp("resolved_at");
                    issue.put("resolvedAt", resolved == null ? null : resolved.toInstant().toString());
                    return issue;
                });
    }

    private Map<String, Object> comment(long id) {
        return jdbc.query("""
                select c.id, c.body, c.external, c.created_at, u.id as u_id, u.username, u.display_name
                from comment c left join user_account u on u.id = c.author_id
                where c.id = :id""", Map.of("id", id), (rs, n) -> {
                    Map<String, Object> comment = new LinkedHashMap<>();
                    comment.put("id", rs.getLong("id"));
                    comment.put("body", rs.getString("body"));
                    comment.put("external", rs.getBoolean("external"));
                    comment.put("author", userMap((Long) rs.getObject("u_id"), rs.getString("username"),
                            rs.getString("display_name")));
                    comment.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return comment;
                }).stream().findFirst().orElse(null);
    }
}
