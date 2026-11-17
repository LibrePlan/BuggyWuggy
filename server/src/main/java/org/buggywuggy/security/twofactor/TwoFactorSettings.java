/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import org.buggywuggy.demo.DemoMode;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether every user logs in with a second factor, set by the administrator in the
 * configuration ({@code buggywuggy.security.two-factor.*}). Never in demo mode: everyone
 * shares the demo account, so a second factor would only lock visitors out.
 */
@Component
public class TwoFactorSettings {

    private final boolean enabled;
    private final String issuer;

    @Autowired
    public TwoFactorSettings(
            @Value("${buggywuggy.security.two-factor.enabled:false}") boolean enabled,
            @Value("${buggywuggy.security.two-factor.issuer:BuggyWuggy}") String issuer, DemoMode demo) {
        this(enabled && !demo.enabled(), issuer);
        if (enabled && demo.enabled()) {
            LoggerFactory.getLogger(TwoFactorSettings.class)
                    .warn("Two-factor login is switched on in the configuration, but stays off in demo mode");
        }
    }

    public TwoFactorSettings(boolean enabled, String issuer) {
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
