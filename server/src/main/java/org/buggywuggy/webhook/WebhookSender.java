/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.buggywuggy.common.BuildVersionController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts the queued webhook deliveries: one at a time per webhook and in order, so a receiver
 * sees the events of an issue in the order they happened. A delivery that does not get a 2xx
 * answer is tried again after 1, 2, 4, … minutes (at most an hour), until {@code max-attempts};
 * then it has failed, and the next one goes. Redirects are not followed.
 *
 * <p>Headers: {@code X-BuggyWuggy-Event}, {@code X-BuggyWuggy-Delivery} (the same for every
 * attempt: use it to ignore a repeat), and {@code X-BuggyWuggy-Signature: sha256=<hex>}, the
 * HMAC-SHA256 of the body with the webhook's secret.
 */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final int BATCH = 20;
    public static final String SIGNATURE = "X-BuggyWuggy-Signature";

    private record Row(long id, long webhookId, String event, String body, int attempts, String url, byte[] secret) {
    }

    private final JdbcTemplate jdbc;
    private final WebhookService webhooks;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final BuildVersionController version;
    private final int maxAttempts;
    private final Duration timeout;
    private final HttpClient http;

    public WebhookSender(JdbcTemplate jdbc, WebhookService webhooks, TransactionTemplate transactions, Clock clock,
            BuildVersionController version, @Value("${buggywuggy.webhooks.max-attempts:10}") int maxAttempts,
            @Value("${buggywuggy.webhooks.timeout:10s}") Duration timeout) {
        this.jdbc = jdbc;
        this.webhooks = webhooks;
        this.transactions = transactions;
        this.clock = clock;
        this.version = version;
        this.maxAttempts = maxAttempts;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Scheduled(fixedDelayString = "${buggywuggy.webhooks.poll-interval:5s}", initialDelayString = "20s")
    public void sendPending() {
        while (Boolean.TRUE.equals(transactions.execute(status -> sendBatch()))) {
            // Something was sent: the next delivery of that webhook may be due now.
        }
    }

    /**
     * Sends what is due, at most one per webhook (the oldest); true when anything was tried. A
     * failed attempt moves its next attempt into the future, so the loop ends.
     */
    private boolean sendBatch() {
        List<Row> rows = jdbc.query("""
                select d.id, d.webhook_id, d.event_type, d.body, d.attempts, w.url, w.secret_encrypted
                from webhook_delivery d join webhook w on w.id = d.webhook_id
                where d.status = 'PENDING' and d.next_attempt_at <= ? and w.active
                  and not exists (select 1 from webhook_delivery e
                                  where e.webhook_id = d.webhook_id and e.status = 'PENDING' and e.id < d.id)
                order by d.id limit ? for update of d skip locked""",
                (rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getString(6), rs.getBytes(7)),
                Timestamp.from(clock.instant()), BATCH);
        for (Row row : rows) {
            send(row);
        }
        return !rows.isEmpty();
    }

    private void send(Row row) {
        int attempts = row.attempts() + 1;
        Integer status = null;
        String error;
        try {
            byte[] body = row.body().getBytes(StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(row.url()))
                    .timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "BuggyWuggy-Webhook/" + version.version().version())
                    .header("X-BuggyWuggy-Event", row.event())
                    .header("X-BuggyWuggy-Delivery", String.valueOf(row.id()))
                    .header(SIGNATURE, "sha256=" + sign(webhooks.secret(row.webhookId(), row.secret()), body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            status = response.statusCode();
            error = status >= 200 && status < 300 ? null : "HTTP " + status;
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        }
        Instant now = clock.instant();
        if (error == null) {
            jdbc.update("""
                    update webhook_delivery set status = 'SENT', attempts = ?, sent_at = ?, response_status = ?,
                                                last_error = null
                    where id = ?""", attempts, Timestamp.from(now), status, row.id());
            return;
        }
        boolean giveUp = attempts >= maxAttempts;
        Duration backoff = Duration.ofMinutes(Math.min(60, 1L << Math.min(attempts - 1, 6)));
        String shortError = error.length() > 500 ? error.substring(0, 500) : error;
        jdbc.update("""
                update webhook_delivery set status = ?, attempts = ?, next_attempt_at = ?, response_status = ?,
                                            last_error = ?
                where id = ?""", giveUp ? "FAILED" : "PENDING", attempts, Timestamp.from(now.plus(backoff)), status,
                shortError, row.id());
        log.warn("Webhook delivery {} to {} failed (attempt {}{}): {}", row.id(), row.url(), attempts,
                giveUp ? ", giving up" : "", shortError);
    }

    /** HMAC-SHA256 of {@code body} with {@code secret}, in lower-case hex. */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deliveries that were sent or failed more than 30 days ago are removed. */
    @Scheduled(cron = "${buggywuggy.webhooks.cleanup-cron:0 27 3 * * *}")
    public void cleanUp() {
        int removed = jdbc.update("delete from webhook_delivery where status <> 'PENDING' and created_at < ?",
                Timestamp.from(clock.instant().minus(Duration.ofDays(30))));
        if (removed > 0) {
            log.info("Removed {} old webhook deliveries", removed);
        }
    }
}
