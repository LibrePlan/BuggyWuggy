/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import java.util.Locale;

/** Helpers for SQL LIKE searches. Queries must use {@code escape '\'}. */
public final class SqlLike {

    private SqlLike() {
    }

    /** A lower-case LIKE pattern matching {@code text} anywhere, with LIKE wildcards escaped. */
    public static String containsPattern(String text) {
        String escaped = text.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
