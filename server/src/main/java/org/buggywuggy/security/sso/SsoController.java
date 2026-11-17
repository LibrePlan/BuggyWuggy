/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import java.util.List;
import org.buggywuggy.security.sso.SsoProperties.PasswordLogin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the login page offers; public. */
@RestController
public class SsoController {

    /** A login button: the browser opens {@code /oauth2/authorization/<id>?next=...}. */
    public record SsoProviderResponse(String id, String name) {
    }

    public record SsoResponse(PasswordLogin passwordLogin, List<SsoProviderResponse> providers) {
    }

    private final SsoProperties properties;

    public SsoController(SsoProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/v1/auth/sso")
    public SsoResponse sso() {
        return new SsoResponse(properties.passwordLogin(), properties.providers().entrySet().stream()
                .map(e -> new SsoProviderResponse(e.getKey(), e.getValue().name(e.getKey())))
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList());
    }
}
