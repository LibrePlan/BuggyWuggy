/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.help;

import org.buggywuggy.common.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pages of the Help menu: {@code user-guide} (logged-in users), {@code admin-guide}
 * (administrators) and {@code license} (everyone). See SecurityConfiguration.
 */
@RestController
public class HelpController {

    private final HelpDocs docs;

    public HelpController(HelpDocs docs) {
        this.docs = docs;
    }

    @GetMapping("/api/v1/help/{page}")
    public HelpDocs.HelpPage page(@PathVariable String page) {
        return docs.page(page).orElseThrow(() -> ApiException.notFound("help.not-found"));
    }
}
