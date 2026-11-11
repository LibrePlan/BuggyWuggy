/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security;

import java.util.Collection;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * Ends a user's sessions, for example after deactivation, a password reset or a change of
 * global permissions. The user then has to log in again and gets up-to-date authorities.
 */
@Component
public class SessionInvalidator {

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public SessionInvalidator(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    public void invalidateAll(String username) {
        invalidateAllExcept(username, null);
    }

    public void invalidateAll(Collection<String> usernames) {
        usernames.forEach(this::invalidateAll);
    }

    /** Ends all sessions of the user except {@code keepSessionId} (may be {@code null}). */
    public void invalidateAllExcept(String username, String keepSessionId) {
        sessions.findByPrincipalName(username).keySet().stream()
                .filter(id -> !id.equals(keepSessionId))
                .forEach(sessions::deleteById);
    }
}
