/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail.inbound;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.buggywuggy.common.ApiNullable;
import jakarta.validation.Valid;
import org.buggywuggy.issue.IssueDtos;
import org.buggywuggy.issue.IssueService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.buggywuggy.mail.inbound.InboundMailProperties.Mailbox;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The mailboxes as configured and what they did (administrators, read only: they are set in the
 * configuration file), and who an issue's external comments go to.
 */
@RestController
public class InboundMailController {

    public record MailboxStatus(String name, @ApiNullable String host, int port, String security,
            @ApiNullable String username, String folder, @ApiNullable String project, @ApiNullable String issueType,
            String accept, String processedFolder, String spamFolder, @ApiNullable String replyAddress,
            @ApiNullable String problem, @ApiNullable Instant lastCheck, @ApiNullable Instant lastSuccess,
            @ApiNullable String lastError, long created, long spam, long ignored) {
    }

    public record InboundMailEntry(long id, String mailbox, @ApiNullable String sender, @ApiNullable String subject,
            Instant processedAt, String action, @ApiNullable String reason, @ApiNullable String issueKey) {
    }

    public record InboundMailStatus(String pollInterval, List<MailboxStatus> mailboxes,
            List<InboundMailEntry> recent) {
    }

    /** {@code address} is null for issues that did not come by mail. */
    public record MailSender(@ApiNullable String address, @ApiNullable String name, boolean canSend) {
    }

    private final AnswerSplitter splitter;
    private final InboundMailPoller poller;
    private final InboundMailProperties properties;
    private final ExternalReplies replies;
    private final IssueService issues;
    private final NamedParameterJdbcTemplate jdbc;

    public InboundMailController(InboundMailPoller poller, InboundMailProperties properties, ExternalReplies replies,
            IssueService issues, NamedParameterJdbcTemplate jdbc, AnswerSplitter splitter) {
        this.splitter = splitter;
        this.poller = poller;
        this.properties = properties;
        this.replies = replies;
        this.issues = issues;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/admin/mail/inbound")
    public InboundMailStatus inbound() {
        Map<String, Map<String, Long>> counts = new HashMap<>();
        jdbc.query("select mailbox, action, count(*) from inbound_mail group by mailbox, action", Map.of(),
                rs -> {
                    counts.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3));
                });
        List<MailboxStatus> boxes = new ArrayList<>();
        int n = 0;
        for (Mailbox box : poller.mailboxes()) {
            n++;
            String name = box.name() == null || box.name().isBlank() ? "mailbox-" + n : box.name().strip();
            InboundMailPoller.State state = poller.state(name);
            Map<String, Long> c = counts.getOrDefault(name, Map.of());
            boxes.add(new MailboxStatus(name, box.host(), box.port(), box.security().name(), box.username(),
                    box.folder(), box.project(), box.issueType(), box.accept().name(), box.processedFolder(),
                    box.spamFolder(), box.replyAddress(), poller.problems().get(name),
                    state == null ? null : state.lastCheck(), state == null ? null : state.lastSuccess(),
                    state == null ? null : state.lastError(), c.getOrDefault("CREATED", 0L),
                    c.getOrDefault("SPAM", 0L), c.getOrDefault("IGNORED", 0L)));
        }
        List<InboundMailEntry> recent = jdbc.query("""
                select m.id, m.mailbox, m.sender, m.subject, m.processed_at, m.action, m.reason,
                       p.key || '-' || i.number as issue_key
                from inbound_mail m
                left join issue i on i.id = m.issue_id
                left join project p on p.id = i.project_id
                order by m.processed_at desc, m.id desc limit 50""", Map.of(),
                (rs, i) -> new InboundMailEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getString(6), rs.getString(7), rs.getString(8)));
        return new InboundMailStatus(properties.pollInterval().toString(), boxes, recent);
    }

    /** A new issue of an answer that came in by e-mail; returns that issue. */
    @PostMapping("/api/v1/issues/{idOrKey}/comments/{commentId}/split")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueDtos.IssueResponse split(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey, @PathVariable long commentId,
            @Valid @RequestBody AnswerSplitter.SplitRequest request) {
        return splitter.split(user, idOrKey, commentId, request);
    }

    @GetMapping("/api/v1/issues/{idOrKey}/mail-sender")
    public MailSender mailSender(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey) {
        IssueService.IssueAccess access = issues.access(user, idOrKey);
        return replies.target(access.issue().getId())
                .map(t -> new MailSender(t.address(), t.name(), replies.canSend()))
                .orElse(new MailSender(null, null, replies.canSend()));
    }
}
