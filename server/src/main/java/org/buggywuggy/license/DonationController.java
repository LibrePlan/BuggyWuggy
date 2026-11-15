/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.buggywuggy.license.LicenseService.SupportStatus;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * For the login page, without logging in: whether this installation supports the project with
 * a donation, and so whether the login page asks for one.
 */
@RestController
public class DonationController {

    /**
     * {@code show}: whether to ask for a donation (no valid donation code); {@code status}: whether
     * this installation has never entered a donation code, has a valid one, or only expired ones.
     */
    public record DonationResponse(boolean show, SupportStatus status) {
    }

    /** The served names of the comic files ({@link DonationComic#FILES}). */
    private static final Map<String, String> COMIC_FILES =
            Map.of("comic.png", "dependency.png", "comic-2x.png", "dependency_2x.png");

    private final LicenseService licenses;
    private final DonationComic comic;

    public DonationController(LicenseService licenses, DonationComic comic) {
        this.licenses = licenses;
        this.comic = comic;
    }

    @GetMapping("/api/v1/donation")
    public DonationResponse donation() {
        SupportStatus status = licenses.supportStatus();
        return new DonationResponse(status != SupportStatus.ACTIVE, status);
    }

    /**
     * The xkcd comic of the donation text ({@code comic.png}, {@code comic-2x.png}), as downloaded
     * by {@link DonationComic}; 404 while it has not been downloaded.
     */
    @GetMapping("/api/v1/donation/{name:comic(?:-2x)?\\.png}")
    public ResponseEntity<Resource> comic(@PathVariable String name, WebRequest request) throws IOException {
        Path file = comic.file(COMIC_FILES.get(name)).orElse(null);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        if (request.checkNotModified(lastModified)) {
            return null;
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .lastModified(lastModified)
                .body(new FileSystemResource(file));
    }
}
