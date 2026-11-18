/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.buggywuggy.license.LicenseCode;
import org.buggywuggy.license.TestDonorCodes;
import org.buggywuggy.mail.MailOutbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

@TestPropertySource(properties = {
        "buggywuggy.inbound-mail.mailboxes[0].name=feedback",
        "buggywuggy.inbound-mail.mailboxes[0].host=127.0.0.1",
        "buggywuggy.inbound-mail.mailboxes[0].port=3143",
        "buggywuggy.inbound-mail.mailboxes[0].security=none",
        "buggywuggy.inbound-mail.mailboxes[0].username=feedback@buggywuggy.org",
        "buggywuggy.inbound-mail.mailboxes[0].password=feedback-secret",
        "buggywuggy.inbound-mail.mailboxes[0].project=FB",
        "buggywuggy.inbound-mail.mailboxes[0].accept=donor-proof",
        "buggywuggy.inbound-mail.mailboxes[1].name=support",
        "buggywuggy.inbound-mail.mailboxes[1].host=127.0.0.1",
        "buggywuggy.inbound-mail.mailboxes[1].port=3143",
        "buggywuggy.inbound-mail.mailboxes[1].security=none",
        "buggywuggy.inbound-mail.mailboxes[1].username=support@example.org",
        "buggywuggy.inbound-mail.mailboxes[1].password=support-secret",
        "buggywuggy.inbound-mail.mailboxes[1].project=SUP",
        "buggywuggy.inbound-mail.mailboxes[1].max-attachment-size=1KB",
        // Unreachable: the other mailboxes are read anyway.
        "buggywuggy.inbound-mail.mailboxes[2].name=broken",
        "buggywuggy.inbound-mail.mailboxes[2].host=127.0.0.1",
        "buggywuggy.inbound-mail.mailboxes[2].port=1",
        "buggywuggy.inbound-mail.mailboxes[2].security=none",
        "buggywuggy.inbound-mail.mailboxes[2].username=nobody",
        "buggywuggy.inbound-mail.mailboxes[2].project=SUP",
        "buggywuggy.inbound-mail.mailboxes[2].timeout=2s",
})
class InboundMailIntegrationTest extends IssueTestSupport {

    @RegisterExtension
    static final GreenMailExtension mail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    @Autowired
    private InboundMailPoller poller;

    @Autowired
    private InboundMailService service;

    @Autowired
    private LicenseCode realCodes;

    @Autowired
    private MailOutbox outbox;

    private GreenMailUser feedback;
    private GreenMailUser support;
    private ApiClient admin;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from inbound_mail");
        jdbc.update("delete from mail_message");
        jdbc.update("delete from import_source");
        ReflectionTestUtils.setField(service, "codes", TestDonorCodes.verifier());
        feedback = mail.setUser("feedback@buggywuggy.org", "feedback@buggywuggy.org", "feedback-secret");
        support = mail.setUser("support@example.org", "support@example.org", "support-secret");
        admin = setupAndLoginAdmin();
        createProject(admin, "FB", "Feedback");
        createProject(admin, "SUP", "Support");
    }

    @AfterEach
    void restore() {
        ReflectionTestUtils.setField(service, "codes", realCodes);
    }

    private static MimeMessage message(String id, String from, String replyTo, String subject, String text)
            throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            protected void updateMessageID() throws jakarta.mail.MessagingException {
                setHeader("Message-ID", id);
            }
        };
        m.setFrom(new InternetAddress(from));
        if (replyTo != null) {
            m.setReplyTo(new InternetAddress[] {new InternetAddress(replyTo)});
        }
        m.setRecipients(Message.RecipientType.TO, "someone@example.org");
        m.setSubject(subject, "UTF-8");
        m.setSentDate(new java.util.Date(1_780_000_000_000L));
        m.setText(text, "UTF-8");
        m.saveChanges();
        return m;
    }

    private MimeMessage feedbackMail(String id, String challenge, String code) throws Exception {
        MimeMessage m = message(id, "BuggyWuggy <bugs@installation.example>", "Dora Donor <dora@acme.example>",
                "BuggyWuggy feature request: Dark mode", """
                        Feedback from a BuggyWuggy installation that supports the project.

                        Type: Feature request
                        Company: Acme BV
                        Version: 0.3.8 (abc123)

                        Please add *dark mode*.""");
        m.setHeader("X-BuggyWuggy-Feedback", "feature");
        m.setHeader("X-BuggyWuggy-Version", "0.3.8 (abc123)");
        if (challenge != null) {
            m.setHeader("X-BuggyWuggy-Donor-Challenge", challenge);
            m.setHeader("X-BuggyWuggy-Donor-Code", code);
        }
        m.setHeader("Auto-Submitted", "no");
        m.saveChanges();
        return m;
    }

    private int count(String user, String password, String folder) throws Exception {
        Store store = Session.getInstance(new Properties()).getStore("imap");
        store.connect("127.0.0.1", 3143, user, password);
        try {
            Folder f = store.getFolder(folder);
            return f.exists() ? f.getMessageCount() : -1;
        } finally {
            store.close();
        }
    }

    @Test
    void mailBecomesUnassignedIssuesPerMailboxAndRefusedMailIsSpam() throws Exception {
        // Support: anyone; plain text with Markdown characters, a small and a too large file.
        MimeMessage printer = message("<printer-1@customer.example>", "Carol Customer <carol@customer.example>", null,
                "Printer *broken*", "");
        MimeMultipart parts = new MimeMultipart();
        MimeBodyPart text = new MimeBodyPart();
        text.setText("""
                # not a heading
                The printer says <error> and stops.
                    at Printer.print(Printer.java:12)
                > earlier mail""", "UTF-8");
        parts.addBodyPart(text);
        MimeBodyPart small = new MimeBodyPart();
        small.setContent("PNG!".getBytes(StandardCharsets.UTF_8), "image/png");
        small.setFileName("photo.png");
        small.setDisposition("attachment");
        parts.addBodyPart(small);
        MimeBodyPart large = new MimeBodyPart();
        large.setContent(new byte[4096], "application/octet-stream");
        large.setFileName("dump.bin");
        large.setDisposition("attachment");
        parts.addBodyPart(large);
        printer.setContent(parts);
        printer.saveChanges();
        support.deliver(printer);
        MimeMessage vacation = message("<away-1@customer.example>", "carol@customer.example", null,
                "Out of office", "I am away.");
        vacation.setHeader("Auto-Submitted", "auto-replied");
        support.deliver(vacation);

        // Feedback: only with a valid donor proof.
        String challenge = "ABCD-EFGH-JKMN-PQRS";
        feedback.deliver(feedbackMail("<fb-1@installation.example>", challenge,
                TestDonorCodes.sign(challenge, LocalDate.now().plusYears(1))));
        feedback.deliver(message("<spam-1@spam.example>", "Spammer <win@spam.example>", null, "You won", "Click"));
        feedback.deliver(feedbackMail("<fb-2@installation.example>", challenge, "BWL1-forged"));
        feedback.deliver(feedbackMail("<fb-3@installation.example>", challenge,
                TestDonorCodes.sign(challenge, LocalDate.now().minusDays(1))));

        poller.checkAll();

        var sup = admin.get("/api/v1/issues/SUP-1");
        assertThat(sup.status()).as(sup.toString()).isEqualTo(200);
        assertThat((String) sup.json("$.summary")).isEqualTo("Printer *broken*");
        assertThat((Object) sup.json("$.assignee")).isNull();
        assertThat((String) sup.json("$.reporter.username")).isEqualTo("carol@customer.example");
        assertThat((String) sup.json("$.reporter.displayName")).isEqualTo("Carol Customer");
        assertThat((String) sup.json("$.origin.kind")).isEqualTo("MAIL");
        assertThat((String) sup.json("$.origin.key")).isEqualTo("carol@customer.example");
        assertThat((String) sup.json("$.description")).startsWith("""
                \\# not a heading
                The printer says \\<error\\> and stops.
                    at Printer.print(Printer.java:12)
                > earlier mail

                ---
                *Received by e-mail from Carol Customer \\<carol@customer.example\\>, sent 2026-""")
                .contains("(mailbox support).*", "larger than 1 KB: dump.bin.*");
        assertThat((List<String>) admin.get("/api/v1/issues/SUP-1/attachments").json("$[*].fileName"))
                .containsExactly("photo.png");
        // The out-of-office reply did not become an issue.
        assertThat(admin.get("/api/v1/issues/SUP-2").status()).isEqualTo(404);

        var fb = admin.get("/api/v1/issues/FB-1");
        assertThat((String) fb.json("$.issueType.name")).isEqualTo("Feature");
        assertThat((String) fb.json("$.summary")).isEqualTo("BuggyWuggy feature request: Dark mode");
        assertThat((String) fb.json("$.reporter.displayName")).isEqualTo("Dora Donor");
        assertThat((List<String>) fb.json("$.labels")).containsExactly("feedback");
        assertThat((String) fb.json("$.description")).contains("Company: Acme BV", "Please add \\*dark mode\\*.");
        assertThat(admin.get("/api/v1/issues/FB-2").status()).isEqualTo(404);
        assertThat(jdbc.queryForList("select reason from inbound_mail where action = 'SPAM' order by reason",
                String.class)).containsExactly("donation expired on " + LocalDate.now().minusDays(1),
                "invalid donor proof", "no donor proof");

        // Nothing is deleted: handled mail is in Processed, spam in Spam.
        assertThat(count("support@example.org", "support-secret", "INBOX")).isZero();
        assertThat(count("support@example.org", "support-secret", "Processed")).isEqualTo(2);
        assertThat(count("feedback@buggywuggy.org", "feedback-secret", "Processed")).isEqualTo(1);
        assertThat(count("feedback@buggywuggy.org", "feedback-secret", "Spam")).isEqualTo(3);

        // The same mail again (e.g. moving failed): no second issue.
        support.deliver(message("<printer-1@customer.example>", "carol@customer.example", null, "Printer", "x"));
        poller.checkAll();
        assertThat(admin.get("/api/v1/issues/SUP-2").status()).isEqualTo(404);
        assertThat(count("support@example.org", "support-secret", "INBOX")).isZero();

        // The overview: all three mailboxes, the broken one with its error.
        var overview = admin.get("/api/v1/admin/mail/inbound");
        assertThat(overview.status()).as(overview.toString()).isEqualTo(200);
        assertThat((List<String>) overview.json("$.mailboxes[*].name")).containsExactly("feedback", "support",
                "broken");
        assertThat((List<Integer>) overview.json("$.mailboxes[*].created")).containsExactly(1, 1, 0);
        assertThat((List<Integer>) overview.json("$.mailboxes[*].spam")).containsExactly(3, 0, 0);
        assertThat((String) overview.json("$.mailboxes[2].lastError")).isNotBlank();
        assertThat((Object) overview.json("$.mailboxes[1].lastError")).isNull();
        assertThat(overview.body()).doesNotContain("secret");
        assertThat(overview.json("$.recent[*].issueKey").toString()).contains("SUP-1", "FB-1");
    }

    @Test
    void externalCommentsAreMailedToTheSenderInternalOnesNot() throws Exception {
        support.deliver(message("<question-1@customer.example>", "Carol Customer <carol@customer.example>", null,
                "How do I export?", "Where is the export button?"));
        poller.checkAll();

        var sender = admin.get("/api/v1/issues/SUP-1/mail-sender");
        assertThat((String) sender.json("$.address")).isEqualTo("carol@customer.example");
        assertThat((Boolean) sender.json("$.canSend")).isTrue();

        var internal = admin.post("/api/v1/issues/SUP-1/comments", "{\"body\": \"She means the CSV export.\"}");
        assertThat(internal.status()).as(internal.toString()).isEqualTo(201);
        assertThat((Boolean) internal.json("$.external")).isFalse();
        var external = admin.post("/api/v1/issues/SUP-1/comments",
                "{\"body\": \"Use **Export** in the menu.\", \"external\": true}");
        assertThat(external.status()).as(external.toString()).isEqualTo(201);
        assertThat((Boolean) external.json("$.external")).isTrue();
        assertThat((List<Boolean>) admin.get("/api/v1/issues/SUP-1/comments").json("$[*].external"))
                .containsExactly(false, true);

        outbox.sendPending();
        List<MimeMessage> toCarol = java.util.Arrays.stream(mail.getReceivedMessages())
                .filter(m -> {
                    try {
                        return GreenMailUtil.getAddressList(m.getRecipients(Message.RecipientType.TO))
                                .contains("carol@customer.example");
                    } catch (Exception e) {
                        return false;
                    }
                }).toList();
        assertThat(toCarol).hasSize(1);
        MimeMessage answer = toCarol.getFirst();
        assertThat(answer.getSubject()).isEqualTo("Re: How do I export? [SUP-1]");
        assertThat(answer.getHeader("In-Reply-To", null)).isEqualTo("<question-1@customer.example>");
        assertThat(GreenMailUtil.getAddressList(answer.getReplyTo())).isEqualTo("support@example.org");
        String body = GreenMailUtil.getBody(answer);
        assertThat(body).contains("Export").doesNotContain("CSV");

        // An issue that did not come by mail has nobody to send to.
        createIssue(admin, "SUP", "Task", "Made by hand");
        assertThat((Object) admin.get("/api/v1/issues/SUP-2/mail-sender").json("$.address")).isNull();
        var refused = admin.post("/api/v1/issues/SUP-2/comments", "{\"body\": \"Hello\", \"external\": true}");
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("comment.no-mail-sender");
    }
}
