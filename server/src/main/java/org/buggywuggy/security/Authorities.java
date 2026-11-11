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

    public static final Set<String> GLOBAL_PERMISSIONS = Set.of(ADMINISTER, CREATE_PROJECT, BROWSE_USERS);
}
