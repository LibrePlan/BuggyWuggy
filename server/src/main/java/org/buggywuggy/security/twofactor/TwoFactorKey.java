/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import java.nio.file.Path;
import org.buggywuggy.common.SecretKeyFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts the TOTP secrets in the database, with {@code data-dir/secrets/two-factor.key}.
 * Without it, every user has to set up two-factor login again.
 */
@Component
public class TwoFactorKey extends SecretKeyFile {

    public TwoFactorKey(@Value("${buggywuggy.data-dir}") Path dataDir) {
        super(dataDir, "two-factor", "two-factor");
    }
}
