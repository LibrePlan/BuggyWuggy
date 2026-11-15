/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Downloading the comic of the donation text, from a local server instead of xkcd.com. */
class DonationComicTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @TempDir
    Path dataDir;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    /** A server for /comics/ that answers every request with {@code answer}: status and body. */
    private URI serve(Function<String, Answer> answer) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/comics/", exchange -> {
            requests.incrementAndGet();
            Answer a = answer.apply(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(a.status(), a.body().length == 0 ? -1 : a.body().length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(a.body());
            }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/comics/");
    }

    record Answer(int status, byte[] body) {
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private Path cacheDir() {
        return dataDir.resolve("cache").resolve("xkcd");
    }

    private List<String> cacheFiles() throws IOException {
        if (!Files.isDirectory(cacheDir())) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(cacheDir())) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void downloadsBothFilesOnceAndServesThemFromTheCache() throws IOException {
        URI source = serve(path -> new Answer(200, PNG));
        DonationComic comic = new DonationComic(dataDir, true, source);
        assertThat(comic.file("dependency.png")).isEmpty();

        assertThat(comic.download()).isTrue();
        assertThat(requests.get()).isEqualTo(2);
        assertThat(cacheFiles()).containsExactly("dependency.png", "dependency_2x.png");
        assertThat(Files.readAllBytes(comic.file("dependency_2x.png").orElseThrow())).isEqualTo(PNG);

        // Never again, also not by a new instance (a restart).
        assertThat(comic.download()).isTrue();
        assertThat(new DonationComic(dataDir, true, source).download()).isTrue();
        new DonationComic(dataDir, true, source).retryDaily();
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void onlyDownloadsWhatIsMissing() throws IOException {
        URI source = serve(path -> new Answer(200, PNG));
        Files.createDirectories(cacheDir());
        Files.write(cacheDir().resolve("dependency.png"), PNG);

        assertThat(new DonationComic(dataDir, true, source).download()).isTrue();
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void refusesAnythingButAPng() throws IOException {
        URI source = serve(path -> new Answer(200, "<html>not an image</html>".getBytes()));
        DonationComic comic = new DonationComic(dataDir, true, source);

        assertThat(comic.download()).isFalse();
        assertThat(cacheFiles()).isEmpty();
        assertThat(comic.file("dependency.png")).isEmpty();
    }

    @Test
    void refusesAFileLargerThanTwoMegabytes() throws IOException {
        byte[] big = Arrays.copyOf(PNG, 2 * 1024 * 1024 + 1);
        URI source = serve(path -> new Answer(200, big));

        assertThat(new DonationComic(dataDir, true, source).download()).isFalse();
        assertThat(cacheFiles()).isEmpty();
    }

    @Test
    void triesAgainAfterAFailure() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        // The first request fails, the ones after it succeed.
        URI source = serve(path -> calls.incrementAndGet() == 1 ? new Answer(503, new byte[0]) : new Answer(200, PNG));
        DonationComic comic = new DonationComic(dataDir, true, source);

        assertThat(comic.download()).isFalse();
        assertThat(comic.file("dependency.png")).isEmpty();
        comic.retryDaily();
        assertThat(cacheFiles()).containsExactly("dependency.png", "dependency_2x.png");
    }

    @Test
    void anUnreachableSourceIsNoError() {
        // Port 9 (discard) on the loopback address: normally nothing listens there.
        DonationComic comic = new DonationComic(dataDir, true, URI.create("http://127.0.0.1:9/comics/"));
        assertThat(comic.download()).isFalse();
    }

    @Test
    void downloadsNothingWhenSwitchedOff() throws IOException {
        URI source = serve(path -> new Answer(200, PNG));
        DonationComic comic = new DonationComic(dataDir, false, source);

        assertThat(comic.download()).isFalse();
        comic.retryDaily();
        assertThat(requests.get()).isZero();
        assertThat(cacheFiles()).isEmpty();
    }

    @Test
    void onlyItsOwnFiles() throws IOException {
        Files.createDirectories(cacheDir());
        Files.write(dataDir.resolve("cache").resolve("secret.png"), PNG);
        DonationComic comic = new DonationComic(dataDir, true, URI.create("http://127.0.0.1:9/comics/"));

        assertThat(comic.file("../secret.png")).isEmpty();
        assertThat(comic.file("other.png")).isEmpty();
    }
}
