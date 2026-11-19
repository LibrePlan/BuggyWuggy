/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import static org.buggywuggy.common.ApiException.notFound;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.buggywuggy.common.ApiNullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rendered mails waiting to be sent. Mails are written in the transaction that creates
 * them and sent by a background job; failures are retried with exponential backoff
 * (1, 2, 4, … minutes, at most an hour) until {@code max-attempts}. Several instances can
 * share the table: each claims its mails with {@code FOR UPDATE SKIP LOCKED}.
 */
@Service
public class MailOutbox {

    private static final Logger log = LoggerFactory.getLogger(MailOutbox.class);
    private static final int BATCH = 50;

    public record FailedMail(long id, String toAddress, String subject, int attempts, @ApiNullable String lastError,
            Instant createdAt) {
    }

    public record OutboxStatus(long pending, long failed, long sentLastDay) {
    }

    private final JdbcTemplate jdbc;
    private final MailTransport transport;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public MailOutbox(JdbcTemplate jdbc, MailTransport transport, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transport = transport;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Queues a mail; call inside the transaction that decides to send it. */
    @Transactional
    public void enqueue(Long recipientId, String toAddress, String subject, String html, String text) {
        enqueue(recipientId, toAddress, null, Map.of(), subject, html, text);
    }

    /**
     * Queues a mail with a Reply-To (null for none) and extra headers (see
     * {@link MailTransport#send(String, String, Map, String, String, String)}).
     */
    @Transactional
    public void enqueue(Long recipientId, String toAddress, String replyTo, Map<String, String> headers,
            String subject, String html, String text) {
        enqueue(recipientId, toAddress, replyTo, headers, subject, html, text, null);
    }

    /** As {@link #enqueue(Long, String, String, Map, String, String, String)}, with an attachment (null for none). */
    @Transactional
    public void enqueue(Long recipientId, String toAddress, String replyTo, Map<String, String> headers,
            String subject, String html, String text, MailAttachment attachment) {
        if (transport.demo()) {
            return; // demo mode: no mail, not even queued
        }
        Timestamp now = Timestamp.from(clock.instant());
        StringBuilder lines = new StringBuilder();
        headers.forEach((name, value) -> lines.append(name).append(": ")
                .append(value.replaceAll("[\\r\\n]+", " ")).append('\n'));
        jdbc.update("""
                insert into mail_message (recipient_id, to_address, reply_to, headers, subject, html_body, text_body,
                                          attachment_name, attachment_type, attachment_data, next_attempt_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", recipientId, toAddress, replyTo,
                headers.isEmpty() ? null : lines.toString(), subject, html, text,
                attachment == null ? null : attachment.name(), attachment == null ? null : attachment.contentType(),
                attachment == null ? null : attachment.data(), now, now);
    }

    /** The headers as stored by {@link #enqueue}: "Name: value" per line. */
    static Map<String, String> headers(String lines) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (lines != null) {
            for (String line : lines.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
                }
            }
        }
        return headers;
    }

    @Scheduled(fixedDelayString = "${buggywuggy.mail.poll-interval:10s}", initialDelayString = "15s")
    public void sendPending() {
        if (!transport.configured()) {
            return;
        }
        // One transaction per batch: the rows stay locked while they are sent.
        while (Boolean.TRUE.equals(transactions.execute(status -> sendBatch()))) {
            // continue with the next batch
        }
    }

    /** Sends one batch; returns whether there may be more. */
    boolean sendBatch() {
        Instant now = clock.instant();
        record Row(long id, String to, String replyTo, String headers, String subject, String html, String text,
                int attempts, MailAttachment attachment) {
        }
        List<Row> rows = jdbc.query("""
                select id, to_address, reply_to, headers, subject, html_body, text_body, attempts,
                       attachment_name, attachment_type, attachment_data from mail_message
                where status = 'PENDING' and next_attempt_at <= ?
                order by next_attempt_at, id limit ? for update skip locked""",
                (rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                        rs.getString(9) == null ? null : new MailAttachment(rs.getString(9), rs.getString(10),
                                rs.getBytes(11))), Timestamp.from(now), BATCH);
        for (Row row : rows) {
            try {
                transport.send(row.to(), row.replyTo(), headers(row.headers()), row.subject(), row.html(), row.text(),
                        row.attachment());
                jdbc.update("update mail_message set status = 'SENT', sent_at = ?, attempts = attempts + 1,"
                        + " last_error = null where id = ?", Timestamp.from(clock.instant()), row.id());
            } catch (RuntimeException e) {
                int attempts = row.attempts() + 1;
                boolean giveUp = attempts >= transport.properties().maxAttempts();
                String error = abbreviate(String.valueOf(e.getMessage()));
                Duration backoff = Duration.ofMinutes(Math.min(60, 1L << Math.min(attempts - 1, 6)));
                jdbc.update("update mail_message set status = ?, attempts = ?, last_error = ?, next_attempt_at = ?"
                                + " where id = ?", giveUp ? "FAILED" : "PENDING", attempts, error,
                        Timestamp.from(clock.instant().plus(backoff)), row.id());
                log.warn("Sending mail {} to {} failed (attempt {}{}): {}", row.id(), row.to(), attempts,
                        giveUp ? ", giving up" : "", error);
                if (isConnectionProblem(e)) {
                    // The server is unreachable: do not try the rest of the batch now.
                    return false;
                }
            }
        }
        return rows.size() == BATCH;
    }

    /** Removes sent and failed mails older than the retention period. */
    @Scheduled(cron = "${buggywuggy.mail.cleanup-cron:0 17 3 * * *}")
    @Transactional
    public void cleanUp() {
        Instant before = clock.instant().minus(transport.properties().retention());
        int removed = jdbc.update("delete from mail_message where status <> 'PENDING' and created_at < ?",
                Timestamp.from(before));
        if (removed > 0) {
            log.info("Removed {} old mails from the outbox", removed);
        }
    }

    @Transactional(readOnly = true)
    public OutboxStatus status() {
        Timestamp dayAgo = Timestamp.from(clock.instant().minus(Duration.ofDays(1)));
        return jdbc.queryForObject("""
                select count(*) filter (where status = 'PENDING'),
                       count(*) filter (where status = 'FAILED'),
                       count(*) filter (where status = 'SENT' and sent_at >= ?)
                from mail_message""", (rs, n) -> new OutboxStatus(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                dayAgo);
    }

    @Transactional(readOnly = true)
    public List<FailedMail> failed() {
        return jdbc.query("""
                select id, to_address, subject, attempts, last_error, created_at from mail_message
                where status = 'FAILED' order by created_at desc, id desc limit 200""",
                (rs, n) -> new FailedMail(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant()));
    }

    /** Puts a failed mail back in the queue, to be sent at the next run. */
    @Transactional
    public void retry(long id) {
        int updated = jdbc.update("""
                update mail_message set status = 'PENDING', attempts = 0, next_attempt_at = ?
                where id = ? and status = 'FAILED'""", Timestamp.from(clock.instant()), id);
        if (updated == 0) {
            throw notFound("mail.not-found", id);
        }
    }

    @Transactional
    public void discard(long id) {
        if (jdbc.update("delete from mail_message where id = ? and status = 'FAILED'", id) == 0) {
            throw notFound("mail.not-found", id);
        }
    }

    private static boolean isConnectionProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException
                    || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String text) {
        return text.length() > 1000 ? text.substring(0, 999) + "…" : text;
    }
}
