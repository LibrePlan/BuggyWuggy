/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

/** The version and commit for the login page, from the build. */
class BuildVersionIntegrationTest extends IntegrationTest {

    @Test
    void versionAndCommitWithoutLogin() {
        var response = client().get("/api/v1/version");
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        // From META-INF/build-info.properties, written by the Maven build: the version in the pom.
        assertThat((String) response.json("$.version")).isNotBlank().isNotEqualTo("development");
        // From git.properties; null when built outside a git repository.
        String commit = response.json("$.commit");
        if (commit != null) {
            assertThat(commit).matches("[0-9a-f]{7,40}");
        }
    }
}
