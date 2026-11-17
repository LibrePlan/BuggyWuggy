/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import static org.assertj.core.api.Assertions.assertThat;

import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** With two-factor login on, single sign-on leaves further factors to the identity provider. */
@TestPropertySource(properties = "buggywuggy.security.two-factor.enabled=true")
class SsoTwoFactorIntegrationTest extends IntegrationTest {

    @DynamicPropertySource
    static void sso(DynamicPropertyRegistry registry) {
        String p = "buggywuggy.security.sso.providers.idp.";
        registry.add(p + "issuer-uri", SsoIntegrationTest.IDP::issuer);
        registry.add(p + "client-id", () -> OidcTestProvider.CLIENT_ID);
        registry.add(p + "client-secret", () -> OidcTestProvider.CLIENT_SECRET);
        registry.add(p + "create-users", () -> "true");
    }

    @Test
    void singleSignOnNeedsNoSecondFactorButAPasswordStillDoes() {
        var setup = client().post("/api/v1/setup/admin", """
                {"setupToken": "%s", "username": "%s", "displayName": "Administrator",
                 "email": "admin@example.org", "password": "%s"}"""
                .formatted(SETUP_TOKEN, ADMIN, ADMIN_PASSWORD));
        assertThat(setup.status()).isEqualTo(201);
        assertThat(client().login(ADMIN, ADMIN_PASSWORD).<String>json("$.step")).isEqualTo("TWO_FACTOR_ENROLLMENT");

        ApiClient alice = client();
        assertThat(SsoIntegrationTest.sso(alice, "idp", "alice", null)).isEqualTo("/");
        assertThat(alice.get("/api/v1/myself").status()).isEqualTo(200);
        assertThat(alice.get("/api/v1/projects").status()).isEqualTo(200);
    }
}
