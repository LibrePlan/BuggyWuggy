/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.util.Arrays;
import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.buggywuggy.notification.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class MailNotificationIntegrationTest extends IssueTestSupport {

    @RegisterExtension
    static final GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired
    NotificationDispatcher dispatcher;
    @Autowired
    MailOutbox outbox;
    @Autowired
    TransactionTemplate transactions;

    ApiClient admin;
    ApiClient ada;
    ApiClient bob;
    long bobId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox_event");
        jdbc.update("delete from mail_message");
        jdbc.update("delete from mail_template");
        jdbc.update("delete from language where code not in ('en', 'nl')");
        jdbc.update("update language set enabled = true, is_default = (code = 'en')");
        admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bugs");
        long adaId = createUser(admin, "ada", "ada-password-123");
        bobId = createUser(admin, "bob", "bob-password-123");
        long developers = roleId("Developers");
        admin.put("/api/v1/projects/BUG/roles/" + developers + "/users/" + adaId, null);
        ada = loginAs("ada", "ada-password-123");
        bob = loginAs("bob", "bob-password-123");
    }

    /** Lets the quiet period pass, then dispatches and sends. */
    private MimeMessage[] deliver() {
        jdbc.update("update outbox_event set created_at = created_at - interval '10 minutes'");
        transactions.executeWithoutResult(s -> dispatcher.dispatch());
        outbox.sendPending();
        return smtp.getReceivedMessages();
    }

    /** The content of the first part with this content type, e.g. text/html. */
    private static String part(Part part, String type) throws Exception {
        if (part.isMimeType(type)) {
            return ((String) part.getContent()).replace("\r\n", "\n");
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String found = part(multipart.getBodyPart(i), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String to(MimeMessage m) throws Exception {
        return Arrays.toString(m.getRecipients(Message.RecipientType.TO));
    }

    @Test
    void changesByOnePersonAreBundledIntoOneMailPerWatcher() throws Exception {
        String key = createIssue(ada, "BUG", "Bug", "Login fails");
        bob.put("/api/v1/issues/" + key + "/watchers/" + bobId, null);
        jdbc.update("delete from outbox_event"); // only the changes below
        int version = ada.get("/api/v1/issues/" + key).json("$.version");
        ada.patch("/api/v1/issues/" + key, """
                {"version": %d, "summary": "Login fails <after> reset"}""".formatted(version));
        ada.post("/api/v1/issues/" + key + "/comments", """
                {"body": "Looking into it.\\nSecond **line**, see %s"}""".formatted(key));

        MimeMessage[] mails = deliver();
        assertThat(mails).hasSize(1); // bob only: ada made the changes, the admin is not involved
        MimeMessage mail = mails[0];
        assertThat(to(mail)).contains("bob@example.org");
        assertThat(mail.getSubject()).isEqualTo("[" + key + "] ada commented: Login fails <after> reset");
        assertThat(part(mail, "text/html")).contains("Login fails &lt;after&gt; reset",
                "Looking into it.<br>\nSecond <strong>line</strong>, see <a href=\"http://bugs.test/browse/" + key + "\">",
                "Summary");
        assertThat(part(mail, "text/plain")).contains("Login fails <after> reset", "Looking into it.\nSecond line");
    }

    @Test
    void bundlesAreHeldBackDuringTheQuietPeriod() {
        String key = createIssue(ada, "BUG", "Bug", "Slow page");
        bob.put("/api/v1/issues/" + key + "/watchers/" + bobId, null);
        transactions.executeWithoutResult(s -> dispatcher.dispatch());
        outbox.sendPending();
        assertThat(smtp.getReceivedMessages()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where processed_at is null", Long.class))
                .isPositive();
        assertThat(deliver()).hasSize(1);
    }

    @Test
    void mailsAreInTheRecipientsLanguageAndUseCustomizedTemplates() throws Exception {
        bob.patch("/api/v1/myself", """
                {"locale": "nl-NL"}""");
        var saved = admin.put("/api/v1/admin/mail/templates/issue-created/nl", """
                {"subject": "Nieuw: {{issue.key}}", "body": "<p>Hoi {{recipient.name}}, zie {{issue.url}}</p>"}""");
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        assertThat((Boolean) saved.json("$.customized")).isTrue();

        String key = createIssue(ada, "BUG", "Task", "Translate");
        bob.put("/api/v1/issues/" + key + "/watchers/" + bobId, null);
        MimeMessage[] mails = deliver();
        assertThat(mails).hasSize(1);
        assertThat(mails[0].getSubject()).isEqualTo("Nieuw: " + key);
        assertThat(part(mails[0], "text/html")).contains("Hoi bob, zie http://bugs.test/browse/" + key);
        assertThat(part(mails[0], "text/plain")).contains("Hoi bob, zie http://bugs.test/browse/" + key);
    }

    @Test
    void respectsPreferencesAndPermissions() {
        String key = createIssue(ada, "BUG", "Bug", "Secret");
        bob.put("/api/v1/issues/" + key + "/watchers/" + bobId, null);

        bob.patch("/api/v1/myself", """
                {"emailNotifications": false}""");
        assertThat((Boolean) bob.get("/api/v1/myself").json("$.emailNotifications")).isFalse();
        assertThat(deliver()).isEmpty();

        bob.patch("/api/v1/myself", """
                {"emailNotifications": true}""");
        // Bob loses access to the project: no mail, although he still watches.
        admin.delete("/api/v1/projects/BUG/roles/" + roleId("Users") + "/groups/" + groupId("users"));
        ada.post("/api/v1/issues/" + key + "/comments", """
                {"body": "Only for the team"}""");
        assertThat(deliver()).isEmpty();

        // Own changes only on request.
        ada.patch("/api/v1/myself", """
                {"notifyOwnChanges": true}""");
        ada.post("/api/v1/issues/" + key + "/comments", """
                {"body": "Note to self"}""");
        MimeMessage[] mails = deliver();
        assertThat(mails).hasSize(1);
    }

    @Test
    void thePreviousAssigneeHearsAboutAReassignment() throws Exception {
        admin.put("/api/v1/projects/BUG/roles/" + roleId("Developers") + "/users/" + bobId, null);
        var created = admin.post("/api/v1/issues", """
                {"project": "BUG", "issueTypeId": %d, "summary": "Hand over", "assigneeId": %d}"""
                .formatted(typeId("Task"), bobId));
        String key = created.json("$.key");
        assertThat(deliver()).hasSize(1); // bob, as the assignee
        smtp.purgeEmailFromAllMailboxes();

        long adaId = jdbc.queryForObject("select id from user_account where username = 'ada'", Long.class);
        int version = admin.get("/api/v1/issues/" + key).json("$.version");
        var assigned = admin.patch("/api/v1/issues/" + key, """
                {"version": %d, "assigneeId": %d}""".formatted(version, adaId));
        assertThat(assigned.status()).as(assigned.toString()).isEqualTo(200);
        MimeMessage[] mails = deliver();
        assertThat(Arrays.stream(mails).map(m -> {
            try {
                return to(m);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).containsExactlyInAnyOrder("[ada@example.org]", "[bob@example.org]");
        assertThat(mails[0].getSubject()).isEqualTo("[" + key + "] Assigned to ada: Hand over");
        assertThat(part(mails[0], "text/plain")).contains("Assignee  bob → ada");
    }

    @Test
    void deletedIssuesAreAnnounced() throws Exception {
        String key = createIssue(admin, "BUG", "Bug", "Duplicate");
        bob.put("/api/v1/issues/" + key + "/watchers/" + bobId, null);
        assertThat(admin.delete("/api/v1/issues/" + key).status()).isEqualTo(204);
        MimeMessage[] mails = deliver();
        assertThat(mails).hasSize(1);
        assertThat(mails[0].getSubject()).isEqualTo("[" + key + "] Deleted: Duplicate");
    }

    @Test
    void invalidTemplatesAreRejectedWithTheirLines() {
        var response = admin.put("/api/v1/admin/mail/templates/issue-updated/en", """
                {"subject": "{{changes}}", "body": "<p>ok</p>\\n{{issue.kee}}\\n{{issue.key"}""");
        assertThat(response.status()).isEqualTo(400);
        assertThat((String) response.json("$.code")).isEqualTo("mail-template.invalid");
        assertThat((List<String>) response.json("$.errors[*].field")).containsExactly("subject", "body", "body");
        assertThat((List<String>) response.json("$.errors[*].message")).containsExactly(
                "{{changes}} can only be used in the body, not in the subject.",
                "Line 2: Unknown keyword {{issue.kee}}.",
                "Line 3: A keyword is not closed with }}.");
    }

    @Test
    void keywordHelpIsInTheAdministratorsLanguage() {
        var english = admin.get("/api/v1/admin/mail/templates/issue-commented/nl");
        assertThat((List<String>) english.json("$.keywords[?(@.name == 'issue.key')].description"))
                .containsExactly("Key of the issue, e.g. BUG-12");
        assertThat((String) english.json("$.subject")).contains("reageerde");

        admin.patch("/api/v1/myself", """
                {"locale": "nl"}""");
        var dutch = admin.get("/api/v1/admin/mail/templates/issue-commented/en");
        assertThat((List<String>) dutch.json("$.keywords[?(@.name == 'issue.key')].description"))
                .containsExactly("Sleutel van het issue, bijvoorbeeld BUG-12");
        assertThat((String) dutch.json("$.name")).isEqualTo("Reactie toegevoegd");
        assertThat((String) dutch.json("$.help.title")).isEqualTo("Beschikbare keywords");
        assertThat((String) dutch.json("$.subject")).contains("commented");
    }

    @Test
    void previewRendersSampleDataSafely() {
        var preview = admin.post("/api/v1/admin/mail/templates/issue-updated/en/preview", """
                {"subject": "[{{issue.key}}] x", "body": "<p onclick=\\"x()\\">{{actor.name}}</p><script>bad()</script>{{changes}}"}""");
        assertThat(preview.status()).as(preview.toString()).isEqualTo(200);
        assertThat((String) preview.json("$.subject")).isEqualTo("[BUG-123] x");
        String html = preview.json("$.html");
        assertThat(html).contains("<p>Ada Lovelace</p>", "In Progress").doesNotContain("script", "onclick");
        assertThat((String) preview.json("$.text")).contains("Ada Lovelace");
        assertThat((List<?>) preview.json("$.problems")).isEmpty();
    }

    @Test
    void resettingGoesBackToTheBuiltInTemplate() {
        admin.put("/api/v1/admin/mail/templates/test-mail/en", """
                {"subject": "Custom", "body": "<p>Custom</p>"}""");
        var list = admin.get("/api/v1/admin/mail/templates");
        assertThat((List<Boolean>) list.json("$[?(@.key == 'test-mail')].languages[?(@.language == 'en')].customized"))
                .containsExactly(true);
        var reset = admin.delete("/api/v1/admin/mail/templates/test-mail/en");
        assertThat((Boolean) reset.json("$.customized")).isFalse();
        assertThat((String) reset.json("$.subject")).isEqualTo("BuggyWuggy test mail");
    }

    @Test
    void testMailIsSentRightAway() throws Exception {
        assertThat(admin.post("/api/v1/admin/mail/test", null).status()).isEqualTo(204);
        MimeMessage[] mails = smtp.getReceivedMessages();
        assertThat(mails).hasSize(1);
        assertThat(to(mails[0])).contains("admin@example.org");
        assertThat(mails[0].getSubject()).isEqualTo("BuggyWuggy test mail");
        assertThat((Boolean) admin.get("/api/v1/admin/mail/status").json("$.configured")).isTrue();
        assertThat(ada.post("/api/v1/admin/mail/test", null).status()).isEqualTo(403);
    }

    @Test
    void failedMailsCanBeRetried() {
        jdbc.update("""
                insert into mail_message (to_address, subject, html_body, text_body, status, attempts,
                                          next_attempt_at, last_error, created_at)
                values ('bob@example.org', 'Hello', '<p>Hi</p>', 'Hi', 'FAILED', 8, now(), 'Connection refused', now())""");
        var failed = admin.get("/api/v1/admin/mail/failed");
        assertThat((List<String>) failed.json("$[*].lastError")).containsExactly("Connection refused");
        long id = ((Number) failed.json("$[0].id")).longValue();
        assertThat(admin.post("/api/v1/admin/mail/failed/" + id + "/retry", null).status()).isEqualTo(204);
        outbox.sendPending();
        assertThat(smtp.getReceivedMessages()).hasSize(1);
        assertThat(jdbc.queryForObject("select status from mail_message where id = ?", String.class, id))
                .isEqualTo("SENT");
    }

    @Test
    void languagesCanBeManaged() {
        var created = admin.post("/api/v1/admin/languages", """
                {"code": "de", "name": "Deutsch"}""");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        // The default first, then by name.
        assertThat((List<String>) bob.get("/api/v1/languages").json("$[*].code")).containsExactly("en", "de", "nl");
        assertThat((String) admin.patch("/api/v1/admin/languages/en", """
                {"enabled": false}""").json("$.code")).isEqualTo("language.default-cannot-be-disabled");
        admin.patch("/api/v1/admin/languages/nl", """
                {"isDefault": true}""");
        assertThat((List<String>) admin.get("/api/v1/admin/languages").json("$[?(@.isDefault == true)].code"))
                .containsExactly("nl");
        // A language without built-in templates uses those of the default language.
        assertThat((String) admin.get("/api/v1/admin/mail/templates/test-mail/de").json("$.subject"))
                .isEqualTo("BuggyWuggy testmail");
        assertThat(admin.delete("/api/v1/admin/languages/de").status()).isEqualTo(204);
    }
}
