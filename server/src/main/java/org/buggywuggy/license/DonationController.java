/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import org.buggywuggy.license.LicenseService.SupportStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private final LicenseService licenses;

    public DonationController(LicenseService licenses) {
        this.licenses = licenses;
    }

    @GetMapping("/api/v1/donation")
    public DonationResponse donation() {
        SupportStatus status = licenses.supportStatus();
        return new DonationResponse(status != SupportStatus.ACTIVE, status);
    }
}
