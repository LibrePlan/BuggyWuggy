/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail.inbound;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Mailboxes that BuggyWuggy reads over IMAP, each into one project. Mail is never deleted: a
 * handled mail is moved to {@code processed-folder}, a refused one to {@code spam-folder}.
 */
@ConfigurationProperties("buggywuggy.inbound-mail")
public record InboundMailProperties(
        @DefaultValue("2m") Duration pollInterval,
        List<Mailbox> mailboxes) {

    public InboundMailProperties {
        mailboxes = mailboxes == null ? List.of() : List.copyOf(mailboxes);
    }

    /** Who may create an issue by mail. */
    public enum Accept {
        /** Everyone (after the checks against mail loops). */
        ANYONE,
        /** Only mail with a valid donor proof, as the feedback form sends; the rest is spam. */
        DONOR_PROOF
    }

    public enum Security {
        SSL, STARTTLS, NONE
    }

    /**
     * One mailbox. {@code address}: where answers go (Reply-To of the mails BuggyWuggy sends for
     * external comments); by default the username, when that is an e-mail address.
     */
    public record Mailbox(
            String name,
            String host,
            @DefaultValue("993") int port,
            @DefaultValue("ssl") Security security,
            String username,
            String password,
            @DefaultValue("INBOX") String folder,
            String project,
            String issueType,
            @DefaultValue("anyone") Accept accept,
            @DefaultValue("Processed") String processedFolder,
            @DefaultValue("Spam") String spamFolder,
            String address,
            @DefaultValue("10MB") org.springframework.util.unit.DataSize maxAttachmentSize,
            @DefaultValue("30s") Duration timeout) {

        public String replyAddress() {
            if (address != null && !address.isBlank()) {
                return address.strip();
            }
            return username != null && username.contains("@") ? username.strip() : null;
        }
    }
}
