/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** Serving the comic of the donation text; tests never download it (see application-test). */
class DonationComicIntegrationTest extends IntegrationTest {

    @Value("${buggywuggy.data-dir}")
    Path dataDir;

    private Path cacheDir() {
        return dataDir.toAbsolutePath().normalize().resolve("cache").resolve("xkcd");
    }

    @AfterEach
    void removeComic() throws IOException {
        for (String name : DonationComic.FILES) {
            Files.deleteIfExists(cacheDir().resolve(name));
        }
    }

    @Test
    void servesTheDownloadedComicWithoutLogin() throws Exception {
        // Not downloaded yet: the login page then shows only the attribution.
        assertThat(client().download("/api/v1/donation/comic.png").status()).isEqualTo(404);

        Files.createDirectories(cacheDir());
        Files.write(cacheDir().resolve("dependency.png"), DonationComicTest.PNG);
        Files.write(cacheDir().resolve("dependency_2x.png"), DonationComicTest.PNG);

        var comic = client().download("/api/v1/donation/comic.png");
        assertThat(comic.status()).isEqualTo(200);
        assertThat(comic.header("Content-Type")).isEqualTo("image/png");
        assertThat(comic.header("Cache-Control")).contains("max-age=604800").contains("public");
        assertThat(comic.body()).isEqualTo(DonationComicTest.PNG);
        assertThat(client().download("/api/v1/donation/comic-2x.png").status()).isEqualTo(200);

        // A browser that has it gets "not modified".
        var conditional = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/donation/comic.png"))
                .header("If-Modified-Since", comic.header("Last-Modified")).build(), BodyHandlers.discarding());
        assertThat(conditional.statusCode()).isEqualTo(304);

        // Nothing else from the cache or the data directory.
        assertThat(client().download("/api/v1/donation/comic-3x.png").status()).isIn(401, 404);
        assertThat(client().download("/api/v1/donation/dependency.png").status()).isIn(401, 404);
    }
}
