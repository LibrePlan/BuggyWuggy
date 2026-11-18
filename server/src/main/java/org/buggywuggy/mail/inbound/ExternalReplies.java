/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail.inbound;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.buggywuggy.mail.HtmlMail;
import org.buggywuggy.mail.IssueMarkdown;
import org.buggywuggy.mail.MailOutbox;
import org.buggywuggy.mail.MailTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Answers by e-mail to the person an issue came from: an external comment is sent to the
 * sender of the mail the issue was made of, as an answer to that mail (In-Reply-To), with the
 * mailbox as Reply-To. Internal comments are never sent to that person.
 */
@Component
public class ExternalReplies {

    private static final Logger log = LoggerFactory.getLogger("org.buggywuggy.inbound-mail");

    /** Who an external comment on an issue goes to. */
    public record Target(String address, String name, String mailbox, String subject, String messageId) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final MailOutbox outbox;
    private final MailTransport transport;
    private final InboundMailPoller mailboxes;
    private final String baseUrl;

    public ExternalReplies(NamedParameterJdbcTemplate jdbc, MailOutbox outbox, MailTransport transport,
            InboundMailPoller mailboxes, @Value("${buggywuggy.base-url}") String baseUrl) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.transport = transport;
        this.mailboxes = mailboxes;
        this.baseUrl = baseUrl;
    }

    /** The sender of the mail the issue was made of; empty for issues that did not come by mail. */
    public Optional<Target> target(long issueId) {
        return jdbc.query("""
                select sender, sender_name, mailbox, subject, message_id from inbound_mail
                where issue_id = :issue and action = 'CREATED' and sender is not null order by id limit 1""",
                Map.of("issue", issueId), (rs, n) -> new Target(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)))
                .stream().findFirst();
    }

    /** Whether external comments can be sent at all. */
    public boolean canSend() {
        return transport.configured();
    }

    /** Queues the comment for the sender, in the transaction of the comment. */
    public void send(Target target, String issueKey, String authorName, String body) {
        String original = target.subject() == null ? "" : target.subject().strip();
        String subject = (original.toLowerCase(Locale.ROOT).startsWith("re:") ? original : "Re: " + original).strip();
        if (!subject.contains("[" + issueKey + "]")) {
            subject = subject + " [" + issueKey + "]";
        }
        String text = body + "\n\n-- \n" + authorName + "\n" + issueKey + "\n";
        String html = "<div>" + IssueMarkdown.toHtml(body, new IssueMarkdown.Links(baseUrl, baseUrl, Set.of()))
                + "</div><p style=\"color: #59636e;\">-- <br>" + HtmlUtils.htmlEscape(authorName) + "<br>"
                + HtmlUtils.htmlEscape(issueKey) + "</p>";

        Map<String, String> headers = new LinkedHashMap<>();
        // Written by a person: an answer is welcome (and is not taken for an automatic mail).
        headers.put("Auto-Submitted", "no");
        if (target.messageId() != null && !target.messageId().startsWith("<sha256-")) {
            headers.put("In-Reply-To", target.messageId());
            headers.put("References", target.messageId());
        }
        headers.put("X-BuggyWuggy-Issue", issueKey);
        var box = mailboxes.mailbox(target.mailbox());
        String replyTo = box == null ? null : box.replyAddress();
        outbox.enqueue(null, target.address(), replyTo, headers, subject, HtmlMail.document(html), text);
        log.info("Inbound mail {}: comment on {} queued for {}", target.mailbox(), issueKey, target.address());
    }
}
