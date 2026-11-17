/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.help;

import static org.assertj.core.api.Assertions.assertThat;

import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

class HelpIntegrationTest extends IntegrationTest {

    @Test
    void theLicenseAndVersionAreForEveryoneTheGuidesForUsersAndAdministrators() {
        var license = client().get("/api/v1/help/license");
        assertThat(license.status()).isEqualTo(200);
        assertThat(license.<String>json("$.html")).contains("GNU AFFERO GENERAL PUBLIC LICENSE");
        assertThat(client().get("/api/v1/version").<String>json("$.sourceUrl"))
                .isEqualTo("https://github.com/libreplan/buggywuggy");
        assertThat(client().get("/api/v1/help/user-guide").status()).isEqualTo(401);
        assertThat(client().get("/api/v1/help/admin-guide").status()).isEqualTo(401);

        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "erin", "erin-password-123");
        ApiClient erin = loginAs("erin", "erin-password-123");
        var guide = erin.get("/api/v1/help/user-guide");
        assertThat(guide.status()).isEqualTo(200);
        assertThat(guide.<String>json("$.title")).isEqualTo("User guide");
        assertThat(erin.get("/api/v1/help/admin-guide").status()).isEqualTo(403);
        assertThat(erin.get("/api/v1/help/nope").status()).isEqualTo(404);

        var adminGuide = admin.get("/api/v1/help/admin-guide");
        assertThat(adminGuide.status()).isEqualTo(200);
        assertThat(adminGuide.<String>json("$.html")).contains("id=\"single-sign-on--settings\"");
    }
}
