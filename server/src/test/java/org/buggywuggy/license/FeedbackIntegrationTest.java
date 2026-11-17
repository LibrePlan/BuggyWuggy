/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDate;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.buggywuggy.mail.MailOutbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

/** Help → Send feedback: only with a valid donation, mailed with the donor proof. */
class FeedbackIntegrationTest extends IntegrationTest {

    @RegisterExtension
    static final GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired
    private LicenseService licenses;

    @Autowired
    private LicenseCode realCodes;

    @Autowired
    private MailOutbox outbox;

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

    private static final String FEEDBACK = """
            {"type": "BUG", "subject": "Crash on\\nsave", "message": "It **crashes**.\\n\\n<script>alert(1)</script>"}""";

    @Test
    void onlyUsersOfASupportingInstallationSendFeedbackWithTheDonorProof() throws Exception {
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "otto", "otto-password-12");
        ApiClient otto = loginAs("otto", "otto-password-12");

        // No donation: the form is disabled, and the server refuses it too.
        var status = otto.get("/api/v1/feedback");
        assertThat(status.status()).as(status.toString()).isEqualTo(200);
        assertThat((Boolean) status.json("$.allowed")).isFalse();
        assertThat((String) status.json("$.donation")).isEqualTo("NONE");
        assertThat((String) status.json("$.to")).isEqualTo("feedback@buggywuggy.org");
        var refused = otto.post("/api/v1/feedback", FEEDBACK);
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.body()).contains("feedback.no-donation");

        var request = admin.post("/api/v1/admin/license/requests", """
                {"country": "NL", "users": 25, "companyName": "Acme BV", "contactName": "Ada",
                 "contactEmail": "ada@acme.example"}""");
        String challenge = request.json("$.challenge");
        String code = LicenseKeys.sign(challenge, LocalDate.now().plusYears(1));
        assertThat(admin.post("/api/v1/admin/license", """
                {"code": "%s"}""".formatted(code)).status()).isEqualTo(200);
        assertThat((Boolean) otto.get("/api/v1/feedback").json("$.allowed")).isTrue();

        // A code entered before codes were kept: no proof until it is entered again.
        jdbc.update("update license set code = null");
        status = otto.get("/api/v1/feedback");
        assertThat((Boolean) status.json("$.allowed")).isFalse();
        assertThat((Boolean) status.json("$.codeMissing")).isTrue();
        assertThat(admin.post("/api/v1/admin/license", """
                {"code": "%s"}""".formatted(code)).status()).isEqualTo(200);
        assertThat((Boolean) otto.get("/api/v1/feedback").json("$.allowed")).isTrue();

        assertThat(otto.post("/api/v1/feedback", """
                {"subject": "No type", "message": "x"}""").status()).isEqualTo(400);
        var sent = otto.post("/api/v1/feedback", FEEDBACK);
        assertThat(sent.status()).as(sent.toString()).isEqualTo(200);
        assertThat((Boolean) sent.json("$.mailConfigured")).isTrue();
        assertThat((String) sent.json("$.subject")).isEqualTo("BuggyWuggy bug report: Crash on save");
        assertThat((String) sent.json("$.text")).contains("Company: Acme BV", "Donor challenge: " + challenge,
                "Donor code: " + code, "otto (otto) <otto@example.org>", "It **crashes**.");

        outbox.sendPending();
        MimeMessage mail = null;
        for (MimeMessage m : smtp.getReceivedMessages()) {
            if (GreenMailUtil.getAddressList(m.getRecipients(Message.RecipientType.TO))
                    .contains("feedback@buggywuggy.org")) {
                mail = m;
            }
        }
        assertThat(mail).isNotNull();
        assertThat(mail.getSubject()).isEqualTo("BuggyWuggy bug report: Crash on save");
        assertThat(GreenMailUtil.getAddressList(mail.getReplyTo())).isEqualTo("otto@example.org");
        assertThat(mail.getHeader("Auto-Submitted", null)).isEqualTo("no");
        assertThat(mail.getHeader("X-BuggyWuggy-Feedback", null)).isEqualTo("bug");
        assertThat(mail.getHeader("X-BuggyWuggy-Donor-Challenge", null)).isEqualTo(challenge);
        // The receiving side can check the proof with the public key.
        assertThat(new LicenseCode(LicenseKeys.PUBLIC)
                .verify(mail.getHeader("X-BuggyWuggy-Donor-Challenge", null),
                        mail.getHeader("X-BuggyWuggy-Donor-Code", null)))
                .contains(LocalDate.now().plusYears(1));
        assertThat(GreenMailUtil.getBody(mail)).contains("crashes", challenge);

        // After the donation expired, nothing more.
        jdbc.update("update license set valid_until = current_date - 1");
        assertThat((Boolean) otto.get("/api/v1/feedback").json("$.allowed")).isFalse();
        assertThat(otto.post("/api/v1/feedback", FEEDBACK).status()).isEqualTo(403);
        assertThat(client().get("/api/v1/feedback").status()).isIn(401, 403);
    }
}
