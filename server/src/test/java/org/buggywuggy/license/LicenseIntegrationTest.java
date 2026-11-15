/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/** Quotation requests, donation codes and the support status, with a key pair of the test. */
class LicenseIntegrationTest extends IntegrationTest {

    @Autowired
    private LicenseService licenses;

    @Autowired
    private LicenseCode realCodes;

    /** The test's key instead of the one in the source code, without a context of its own. */
    @BeforeEach
    void useTestKey() {
        removeLicenses();
        ReflectionTestUtils.setField(licenses, "codes", new LicenseCode(LicenseKeys.PUBLIC));
    }

    @AfterEach
    void restore() {
        ReflectionTestUtils.setField(licenses, "codes", realCodes);
        removeLicenses();
    }

    private void removeLicenses() {
        jdbc.update("delete from license");
        jdbc.update("delete from license_request");
        jdbc.update("delete from mail_message");
    }

    private int mailsToSales() {
        return jdbc.queryForObject("select count(*) from mail_message where to_address = 'sales@buggywuggy.org'",
                Integer.class);
    }

    @Test
    void donationCodeMakesAnActiveSupporterUntilItExpires() {
        assertThat((Boolean) client().get("/api/v1/donation").json("$.show")).isTrue();
        assertThat((String) client().get("/api/v1/donation").json("$.status")).isEqualTo("NONE");
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "otto", "otto-password-12");

        var status = admin.get("/api/v1/admin/license");
        assertThat(status.status()).as(status.toString()).isEqualTo(200);
        assertThat((Boolean) status.json("$.licensed")).isFalse();
        assertThat((Integer) status.json("$.userCount")).isEqualTo(2);
        assertThat((String) admin.get("/api/v1/admin/license/countries").json("$[?(@.code == 'NL')].dialCode")
                .toString()).contains("+31");

        var quote = admin.get("/api/v1/admin/license/quote?country=NL");
        assertThat(quote.status()).as(quote.toString()).isEqualTo(200);
        assertThat((Integer) quote.json("$.users")).isEqualTo(2);
        assertThat(((Number) quote.json("$.yearlyTotal")).doubleValue()).isEqualTo(168.0);

        var request = admin.post("/api/v1/admin/license/requests", """
                {"country": "NL", "users": 25, "companyName": "Acme <BV>", "contactName": "Ada",
                 "contactEmail": "ada@acme.example", "contactPhone": "+31 20 123 4567"}""");
        assertThat(request.status()).as(request.toString()).isEqualTo(200);
        String challenge = request.json("$.challenge");
        assertThat((String) request.json("$.text")).contains(challenge, "Acme <BV>", "2100.00 EUR", "+31 20 123 4567");
        assertThat(mailsToSales()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select html_body from mail_message", String.class))
                .contains("Acme &lt;BV&gt;").doesNotContain("<BV>");
        assertThat((String) admin.get("/api/v1/admin/license").json("$.requests[0].challenge")).isEqualTo(challenge);

        // A code for another challenge, or one that has expired.
        assertThat(admin.post("/api/v1/admin/license", """
                {"code": "%s"}""".formatted(LicenseKeys.sign("BW-0000-0000-0000-0000", LocalDate.now().plusYears(1))))
                .status()).isEqualTo(400);
        assertThat(admin.post("/api/v1/admin/license", """
                {"code": "%s"}""".formatted(LicenseKeys.sign(challenge, LocalDate.now().minusDays(1))))
                .status()).isEqualTo(400);

        LocalDate until = LocalDate.now().plusYears(1);
        var activated = admin.post("/api/v1/admin/license", """
                {"code": "%s"}""".formatted(LicenseKeys.sign(challenge, until)));
        assertThat(activated.status()).as(activated.toString()).isEqualTo(200);
        assertThat((Boolean) activated.json("$.licensed")).isTrue();
        assertThat((String) activated.json("$.validUntil")).isEqualTo(until.toString());
        assertThat((Boolean) client().get("/api/v1/donation").json("$.show")).isFalse();
        assertThat((String) client().get("/api/v1/donation").json("$.status")).isEqualTo("ACTIVE");

        // No reminder yet; one, once, when the license ends within a month.
        licenses.remindBeforeExpiry();
        assertThat(mailsToSales()).isEqualTo(1);
        jdbc.update("update license set valid_until = current_date + 20");
        licenses.remindBeforeExpiry();
        licenses.remindBeforeExpiry();
        assertThat(mailsToSales()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select subject from mail_message order by id desc limit 1", String.class))
                .startsWith("BuggyWuggy donation expires on");

        // After the last day, the support has expired and the request to donate is back.
        jdbc.update("update license set valid_until = current_date - 1");
        assertThat((Boolean) client().get("/api/v1/donation").json("$.show")).isTrue();
        assertThat((String) client().get("/api/v1/donation").json("$.status")).isEqualTo("EXPIRED");

        // Only for administrators.
        ApiClient otto = loginAs("otto", "otto-password-12");
        assertThat(otto.get("/api/v1/admin/license").status()).isEqualTo(403);
        assertThat(client().post("/api/v1/admin/license/requests", "{}").status()).isIn(401, 403);
    }
}
