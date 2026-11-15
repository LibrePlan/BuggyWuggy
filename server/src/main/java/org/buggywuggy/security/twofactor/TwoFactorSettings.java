/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether every user logs in with a second factor, set by the administrator in the
 * configuration ({@code buggywuggy.security.two-factor.*}).
 */
@Component
public class TwoFactorSettings {

    private final boolean enabled;
    private final String issuer;

    public TwoFactorSettings(
            @Value("${buggywuggy.security.two-factor.enabled:false}") boolean enabled,
            @Value("${buggywuggy.security.two-factor.issuer:BuggyWuggy}") String issuer) {
        this.enabled = enabled;
        this.issuer = issuer.isBlank() ? "BuggyWuggy" : issuer.strip();
    }

    public boolean enabled() {
        return enabled;
    }

    /** The name authenticator apps show above the code. */
    public String issuer() {
        return issuer;
    }
}
