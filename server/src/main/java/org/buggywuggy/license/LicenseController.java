/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import jakarta.validation.Valid;
import java.util.List;
import org.buggywuggy.license.LicensePricing.Country;
import org.buggywuggy.license.LicensePricing.Quote;
import org.buggywuggy.license.LicenseService.ActivateRequest;
import org.buggywuggy.license.LicenseService.LicenseStatus;
import org.buggywuggy.license.LicenseService.QuotationRequest;
import org.buggywuggy.license.LicenseService.QuotationResponse;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The annual donation of this installation: quotation requests and donation codes. Requires the
 * ADMINISTER global permission (see SecurityConfiguration).
 */
@RestController
@RequestMapping("/api/v1/admin/license")
public class LicenseController {

    private final LicenseService licenses;
    private final LicensePricing pricing;

    public LicenseController(LicenseService licenses, LicensePricing pricing) {
        this.licenses = licenses;
        this.pricing = pricing;
    }

    @GetMapping
    public LicenseStatus status() {
        return licenses.status();
    }

    @GetMapping("/countries")
    public List<Country> countries() {
        return pricing.countries();
    }

    /** The price for a country; {@code users} defaults to the number of active accounts. */
    @GetMapping("/quote")
    public Quote quote(@RequestParam String country, @RequestParam(required = false) Integer users) {
        return licenses.quote(country, users);
    }

    /** Mails a quotation request with a new challenge to sales@buggywuggy.org. */
    @PostMapping("/requests")
    public QuotationResponse request(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @Valid @RequestBody QuotationRequest request) {
        return licenses.requestQuotation(user, request);
    }

    /** Enters a donation code. */
    @PostMapping
    public LicenseStatus activate(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @Valid @RequestBody ActivateRequest request) {
        return licenses.activate(user, request);
    }
}
