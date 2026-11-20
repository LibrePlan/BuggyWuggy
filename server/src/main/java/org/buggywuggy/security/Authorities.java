/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security;

import java.util.Set;

/** Spring Security authority names used for global (non-project) permissions. */
public final class Authorities {

    private Authorities() {
    }

    /** Full control over the installation; implies every global permission. */
    public static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";

    /** Global permissions, granted to groups through {@code global_permission_grant}. */
    public static final String ADMINISTER = "ADMINISTER";
    public static final String CREATE_PROJECT = "CREATE_PROJECT";
    public static final String BROWSE_USERS = "BROWSE_USERS";

    /**
     * Marks a login that passed the second factor. With two-factor login enabled, a session
     * without it (e.g. from before it was enabled) is ended; see TwoFactorSessionFilter.
     */
    public static final String TWO_FACTOR_VERIFIED = "TWO_FACTOR_VERIFIED";

    /**
     * Marks a login through single sign-on. The identity provider is responsible for further
     * factors, so with two-factor login enabled such a session is accepted too.
     */
    public static final String SSO_LOGIN = "SSO_LOGIN";

    /**
     * Marks a request made with a personal API token (see ApiTokenFilter): it has no session,
     * and some actions (making tokens) need a real login.
     */
    public static final String API_TOKEN = "API_TOKEN";

    public static final Set<String> GLOBAL_PERMISSIONS = Set.of(ADMINISTER, CREATE_PROJECT, BROWSE_USERS);
}
