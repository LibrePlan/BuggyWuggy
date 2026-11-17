/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import org.buggywuggy.demo.DemoMode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.GitProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The version of BuggyWuggy and the commit it was built from, for the login and About pages, without
 * logging in. Both come from the build (META-INF/build-info.properties and git.properties).
 */
@RestController
public class BuildVersionController {

    /**
     * {@code version}: as in the pom, {@code "development"} when not built with Maven;
     * {@code commit}: the abbreviated git commit id, null when built without git;
     * {@code sourceUrl}: where to get the source code (AGPL section 13).
     */
    public record BuildVersionResponse(String version, @ApiNullable String commit, String sourceUrl,
            @ApiNullable DemoInfo demo) {
    }

    /** Set in demo mode: the shared account, filled in on the login page, and its project. */
    public record DemoInfo(String username, String password, String projectKey) {
    }

    private final BuildVersionResponse response;

    public BuildVersionController(ObjectProvider<BuildProperties> build, ObjectProvider<GitProperties> git,
            @Value("${buggywuggy.source-url}") String sourceUrl, DemoMode demo) {
        BuildProperties b = build.getIfAvailable();
        GitProperties g = git.getIfAvailable();
        this.response = new BuildVersionResponse(b == null ? "development" : b.getVersion(),
                g == null ? null : g.getShortCommitId(), sourceUrl, demo.enabled()
                        ? new DemoInfo(DemoMode.USERNAME, DemoMode.PASSWORD, DemoMode.PROJECT_KEY)
                        : null);
    }

    @GetMapping("/api/v1/version")
    public BuildVersionResponse version() {
        return response;
    }
}
