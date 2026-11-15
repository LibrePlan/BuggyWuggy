/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The version of BuggyWuggy and the commit it was built from, for the login page, without
 * logging in. Both come from the build (META-INF/build-info.properties and git.properties).
 */
@RestController
public class BuildVersionController {

    /**
     * {@code version}: as in the pom, {@code "development"} when not built with Maven;
     * {@code commit}: the abbreviated git commit id, null when built without git.
     */
    public record BuildVersionResponse(String version, @ApiNullable String commit) {
    }

    private final BuildVersionResponse response;

    public BuildVersionController(ObjectProvider<BuildProperties> build, ObjectProvider<GitProperties> git) {
        BuildProperties b = build.getIfAvailable();
        GitProperties g = git.getIfAvailable();
        this.response = new BuildVersionResponse(b == null ? "development" : b.getVersion(),
                g == null ? null : g.getShortCommitId());
    }

    @GetMapping("/api/v1/version")
    public BuildVersionResponse version() {
        return response;
    }
}
