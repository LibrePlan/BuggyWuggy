/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code buggywuggy.mail.*} in the configuration file. Without a {@code host}, BuggyWuggy
 * sends no mail.
 *
 * @param quietPeriod changes by the same person to the same issue are bundled into one mail
 *     once nothing happened for this long
 * @param maxDelay but a bundle is never held back longer than this
 */
@ConfigurationProperties("buggywuggy.mail")
public record MailProperties(
        String host,
        @DefaultValue("587") int port,
        String username,
        String password,
        @DefaultValue("starttls") Security security,
        @DefaultValue("BuggyWuggy <buggywuggy@localhost>") String from,
        @DefaultValue("60s") Duration quietPeriod,
        @DefaultValue("5m") Duration maxDelay,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("30d") Duration retention,
        @DefaultValue("30s") Duration timeout) {

    public enum Security {
        STARTTLS, SSL, NONE
    }

    public boolean configured() {
        return host != null && !host.isBlank();
    }
}
