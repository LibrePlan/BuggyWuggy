/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** For the login page, without logging in: whether it asks for a donation. */
@RestController
public class DonationController {

    public record DonationResponse(boolean show) {
    }

    private final LicenseService licenses;

    public DonationController(LicenseService licenses) {
        this.licenses = licenses;
    }

    @GetMapping("/api/v1/donation")
    public DonationResponse donation() {
        return new DonationResponse(licenses.showDonation());
    }
}
