/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The xkcd comic 2347 "Dependency" (Randall Munroe, CC BY-NC 2.5) in the donation text of the
 * login page. It is not part of BuggyWuggy, as its NonCommercial license does not fit the AGPL:
 * the server downloads it once, after startup, into the data directory and serves it from
 * there, so visitors never contact xkcd.com. When the download fails it tries again once a
 * day; while the comic is missing, the login page shows only its attribution.
 */
@Component
public class DonationComic {

    /** The files at the source: the comic and its double-resolution version. */
    static final List<String> FILES = List.of("dependency.png", "dependency_2x.png");

    private static final Logger log = LoggerFactory.getLogger(DonationComic.class);
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private final Path dir;
    private final boolean enabled;
    private final URI source;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final AtomicBoolean downloading = new AtomicBoolean();

    public DonationComic(@Value("${buggywuggy.data-dir}") Path dataDir,
            @Value("${buggywuggy.donation.comic.download:true}") boolean enabled,
            @Value("${buggywuggy.donation.comic.source:https://imgs.xkcd.com/comics/}") URI source) {
        this.dir = dataDir.toAbsolutePath().normalize().resolve("cache").resolve("xkcd");
        this.enabled = enabled;
        this.source = source;
    }

    /** The downloaded file, if it is there; {@code name} is one of {@link #FILES}. */
    public Optional<Path> file(String name) {
        if (!FILES.contains(name)) {
            return Optional.empty();
        }
        Path file = dir.resolve(name);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    private boolean complete() {
        return FILES.stream().allMatch(name -> file(name).isPresent());
    }

    /** In the background, so that a slow or unreachable xkcd.com does not delay the startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void downloadAfterStartup() {
        if (enabled && !complete()) {
            Thread.ofVirtual().name("donation-comic").start(this::download);
        }
    }

    /** Once a day, as long as the comic is missing. */
    @Scheduled(cron = "0 17 4 * * *")
    public void retryDaily() {
        if (enabled && !complete()) {
            download();
        }
    }

    /**
     * Downloads the files that are missing; files that are there are never downloaded again.
     *
     * @return whether all files are there afterwards
     */
    public boolean download() {
        if (complete()) {
            return true;
        }
        if (!enabled || !downloading.compareAndSet(false, true)) {
            return false;
        }
        try {
            Files.createDirectories(dir);
            for (String name : FILES) {
                if (file(name).isEmpty()) {
                    fetch(name);
                }
            }
            log.info("Downloaded the xkcd comic of the donation text into {}", dir);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not download the xkcd comic of the donation text from {}: {}. Trying again once a day; "
                    + "BUGGYWUGGY_DONATION_COMIC_DOWNLOAD=false stops this.", source, e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            downloading.set(false);
        }
    }

    private void fetch(String name) throws IOException, InterruptedException {
        URI uri = source.resolve(name);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "BuggyWuggy")
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request, BodyHandlers.limiting(BodyHandlers.ofByteArray(), MAX_BYTES));
        HttpResponse<byte[]> response;
        try {
            // Bounds the whole download, the body included, not only the wait for the headers.
            response = pending.get(DOWNLOAD_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new IOException("no answer from " + uri + " within " + DOWNLOAD_TIMEOUT.toSeconds() + " seconds");
        } catch (ExecutionException e) {
            throw new IOException(uri + ": " + e.getCause(), e.getCause());
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        byte[] bytes = response.body();
        if (bytes.length < PNG_SIGNATURE.length
                || !Arrays.equals(bytes, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            throw new IOException(uri + " is not a PNG image");
        }
        // Never a half-written file: write next to it, then rename.
        Path temp = Files.createTempFile(dir, name, ".part");
        try {
            Files.write(temp, bytes);
            Files.move(temp, dir.resolve(name), ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
