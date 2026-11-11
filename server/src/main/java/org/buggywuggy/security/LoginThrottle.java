/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits password guessing: after {@code max-failures} failed logins for the same username
 * from the same client address, further attempts are refused until the window has passed.
 *
 * <p>Keyed on username <em>and</em> address, so an attacker cannot lock a user out from
 * elsewhere. State is kept in memory per node; that is sufficient for brute-force protection.
 */
@Component
public class LoginThrottle {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private record Window(int failures, Instant start) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;
    private final Clock clock;

    public LoginThrottle(
            @Value("${buggywuggy.security.login-throttle.max-failures:5}") int maxFailures,
            @Value("${buggywuggy.security.login-throttle.window:15m}") Duration window,
            Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    public static String key(String username, String clientAddress) {
        return username.trim().toLowerCase(Locale.ROOT) + '|' + clientAddress;
    }

    public boolean isBlocked(String key) {
        Window w = windows.get(key);
        if (w == null) {
            return false;
        }
        if (expired(w)) {
            windows.remove(key, w);
            return false;
        }
        return w.failures() >= maxFailures;
    }

    public void recordFailure(String key) {
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.values().removeIf(this::expired);
        }
        windows.compute(key, (k, w) -> w == null || expired(w)
                ? new Window(1, clock.instant())
                : new Window(w.failures() + 1, w.start()));
    }

    public void recordSuccess(String key) {
        windows.remove(key);
    }

    private boolean expired(Window w) {
        return clock.instant().isAfter(w.start().plus(window));
    }
}
