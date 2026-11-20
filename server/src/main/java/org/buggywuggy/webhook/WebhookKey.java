/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.webhook;

import java.nio.file.Path;
import org.buggywuggy.common.SecretKeyFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts the signing secrets of the webhooks in the database, with
 * {@code data-dir/secrets/webhook.key}. Without it, every webhook needs a new secret.
 */
@Component
public class WebhookKey extends SecretKeyFile {

    public WebhookKey(@Value("${buggywuggy.data-dir}") Path dataDir) {
        super(dataDir, "webhook", "webhook");
    }
}
