/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.buggywuggy.importer.FakeForgejo.asset;
import static org.buggywuggy.importer.FakeForgejo.comment;
import static org.buggywuggy.importer.FakeForgejo.event;
import static org.buggywuggy.importer.FakeForgejo.issue;
import static org.buggywuggy.importer.FakeForgejo.label;
import static org.buggywuggy.importer.FakeForgejo.list;
import static org.buggywuggy.importer.FakeForgejo.map;
import static org.buggywuggy.importer.FakeForgejo.milestone;
import static org.buggywuggy.importer.FakeForgejo.user;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ForgejoImportIntegrationTest extends IssueTestSupport {

    private static final String UUID = "174a5a54-650a-480b-80d7-168dfa4be341";

    private FakeForgejo forgejo;
    private String base;
    private ApiClient admin;
    private Map<String, Object> alice;
    private Map<String, Object> bob;
    private Map<String, Object> carol;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from import_source");
        jdbc.update("delete from outbox_event");
        forgejo = new FakeForgejo();
        base = forgejo.url();
        admin = setupAndLoginAdmin();
        createUser(admin, "alice", "alice-password-123");

        alice = user(11, "alice", "Alice Liddell", "alice@example.org");
        bob = user(12, "bob", "Bob Builder", "bob@noreply.example.org");
        carol = user(13, "carol", "", "carol@noreply.example.org");

        forgejo.json("/version", map("version", "13.0.0+gitea-1.22.0"));
        forgejo.json("/user", user(1, "importer", "Importer", "importer@example.org"));
        forgejo.json("/repos/acme/widgets", map("id", 7, "full_name", "acme/widgets", "name", "widgets",
                "html_url", base + "/acme/widgets", "description", "Widgets", "has_issues", true,
                "open_issues_count", 1, "owner", alice, "private", false, "external_tracker", null,
                "archived", false));
        forgejo.json("/repos/acme/widgets/labels", list(label(1, "kind/bug"), label(2, "needs info")));
        forgejo.json("/repos/acme/widgets/milestones", list(milestone(31, "v1.0", "closed")));

        Map<String, Object> one = issue(base, 101, 1, alice, "Crash on start",
                "Starting crashes, see #3 and the fix in #2.\n\n![shot](/attachments/" + UUID + ")\n\n"
                        + "```\nnot #3 here\n```", "closed");
        one.put("labels", list(label(1, "kind/bug"), label(2, "needs info")));
        one.put("assignees", list(bob));
        one.put("milestone", milestone(31, "v1.0", "closed"));
        one.put("comments", 1);
        one.put("closed_at", "2026-01-09T12:00:00+01:00");
        one.put("assets", list(asset(501, "shot.png", UUID, base)));
        Map<String, Object> pull = issue(base, 102, 2, bob, "Fix crash", "Fixes #1", "closed");
        pull.put("pull_request", map("merged", true, "html_url", base + "/acme/widgets/pulls/2"));
        pull.put("html_url", base + "/acme/widgets/pulls/2");
        Map<String, Object> three = issue(base, 103, 3, bob, "Document the start", "", "open");
        forgejo.json("/repos/acme/widgets/issues", list(one, pull, three));

        forgejo.file("/attachments/" + UUID, "PNG!".getBytes(StandardCharsets.UTF_8));
        forgejo.json("/repos/acme/widgets/issues/1/comments",
                list(comment(base, 901, carol, "Same here, see #3.", "2026-01-06T09:00:00+01:00")));
        forgejo.json("/repos/acme/widgets/issues/1/timeline", list(
                event(1, "label", alice, "2026-01-05T10:00:00+01:00", "body", "1", "label", label(1, "kind/bug")),
                event(2, "comment", carol, "2026-01-06T09:00:00+01:00", "body", "Same here, see #3."),
                event(3, "label", bob, "2026-01-07T09:00:00+01:00", "body", "1", "label", label(2, "needs info")),
                event(4, "change_title", bob, "2026-01-07T09:00:01+01:00", "old_title", "Crash",
                        "new_title", "Crash on start"),
                event(5, "pull_ref", bob, "2026-01-08T09:00:00+01:00", "ref_issue", pull),
                event(6, "close", alice, "2026-01-09T12:00:00+01:00")));
        forgejo.json("/repos/acme/widgets/issues/1/dependencies", list(three));
        forgejo.json("/repos/acme/widgets/issues/3/timeline", list());
        forgejo.json("/repos/acme/widgets/issues/3/dependencies", list());
    }

    @AfterEach
    void tearDown() {
        forgejo.close();
    }

    private ApiClient.Response start(String projectKey) {
        return admin.post("/api/v1/admin/import/forgejo/jobs", """
                {"url": "%s", "token": "%s", "repository": "acme/widgets",
                 "newProject": {"key": "%s", "name": "Widgets"}, "issueTypeId": %d,
                 "labels": {"kind/bug": {"target": "ISSUE_TYPE", "id": %d}}, "users": {}, "matchUsernames": true}"""
                .formatted(base, FakeForgejo.TOKEN, projectKey, typeId("Task"), typeId("Bug")));
    }

    private Map<String, Object> waitFor(long jobId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Map<String, Object> job = admin.get("/api/v1/admin/import/jobs/" + jobId).json("$");
            if (!"RUNNING".equals(job.get("status"))) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("import did not finish");
    }

    private long jobId(ApiClient.Response response) {
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return ((Number) response.json("$.id")).longValue();
    }

    @Test
    void testsTheConnectionAndAnalysesTheRepository() {
        var connection = admin.post("/api/v1/admin/import/forgejo/connection",
                "{\"url\": \"%s/api/v1/\", \"token\": \"%s\"}".formatted(base, FakeForgejo.TOKEN));
        assertThat(connection.status()).as(connection.toString()).isEqualTo(200);
        assertThat((String) connection.json("$.login")).isEqualTo("importer");
        assertThat((String) connection.json("$.url")).isEqualTo(base);

        var refused = admin.post("/api/v1/admin/import/forgejo/connection",
                "{\"url\": \"%s\", \"token\": \"wrong\"}".formatted(base));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).contains("refused");

        var analysis = admin.post("/api/v1/admin/import/forgejo/analysis",
                "{\"url\": \"%s\", \"repository\": \"acme/widgets\"}".formatted(base));
        assertThat(analysis.status()).as(analysis.toString()).isEqualTo(200);
        assertThat((Integer) analysis.json("$.issues")).isEqualTo(2); // the pull request is not an issue
        assertThat((Integer) analysis.json("$.openIssues")).isEqualTo(1);
        assertThat((String) analysis.json("$.labels[0].suggestion.target")).isEqualTo("ISSUE_TYPE");
        assertThat((String) analysis.json("$.labels[1].suggestion.target")).isEqualTo("LABEL");
        List<String> matched = analysis.json("$.users[?(@.login == 'alice')].suggestion.username");
        assertThat(matched).containsExactly("alice"); // same e-mail address
        List<Object> unmatched = analysis.json("$.users[?(@.login == 'bob')].suggestion");
        assertThat(unmatched).containsExactly((Object) null);
    }

    @Test
    void importsIssuesWithTheirHistoryCommentsAttachmentsAndLinks() throws Exception {
        long jobId = jobId(start("WID"));
        Map<String, Object> job = waitFor(jobId);
        assertThat(job.get("status")).as(job.toString()).isEqualTo("SUCCEEDED");
        assertThat(job).extractingByKey("counters").asString()
                .contains("issuesTotal=2", "issuesImported=2", "comments=1", "attachments=1", "usersCreated=2");

        // New numbers in the order of the original ones; the original is kept as origin.
        var one = admin.get("/api/v1/issues/WID-1");
        assertThat((String) one.json("$.summary")).isEqualTo("Crash on start");
        assertThat((String) one.json("$.origin.key")).isEqualTo("acme/widgets#1");
        assertThat((String) one.json("$.origin.url")).isEqualTo(base + "/acme/widgets/issues/1");
        assertThat((String) one.json("$.origin.kind")).isEqualTo("FORGEJO");
        assertThat((String) admin.get("/api/v1/issues/WID-2").json("$.origin.key")).isEqualTo("acme/widgets#3");

        assertThat((String) one.json("$.issueType.name")).isEqualTo("Bug");
        assertThat((List<String>) one.json("$.labels")).containsExactly("needs-info");
        assertThat((String) one.json("$.status.name")).isEqualTo("Closed");
        assertThat((String) one.json("$.resolution.name")).isEqualTo("Done");
        assertThat((String) one.json("$.reporter.username")).isEqualTo("alice");
        assertThat((String) one.json("$.assignee.username")).isEqualTo("bob");
        assertThat((String) one.json("$.fixVersions[0].name")).isEqualTo("v1.0");
        assertThat((String) one.json("$.createdAt")).isEqualTo("2026-01-05T09:00:00Z");
        assertThat((String) one.json("$.resolvedAt")).isEqualTo("2026-01-09T11:00:00Z");
        assertThat((String) one.json("$.remoteLinks[0].url")).isEqualTo(base + "/acme/widgets/pulls/2");
        assertThat((String) one.json("$.remoteLinks[0].relation")).isEqualTo("PULL_REQUEST");

        // References: an imported issue becomes its key, the pull request a link; code is left alone.
        long attachmentId = jdbc.queryForObject(
                "select a.id from attachment a join issue i on i.id = a.issue_id where i.origin_key = 'acme/widgets#1'",
                Long.class);
        assertThat((String) one.json("$.description"))
                .contains("see WID-2 and the fix in [#2](" + base + "/acme/widgets/issues/2)")
                .contains("![shot](/api/v1/attachments/" + attachmentId + "/content)")
                .contains("not #3 here");
        assertThat(admin.download("/api/v1/attachments/" + attachmentId + "/content").body())
                .isEqualTo("PNG!".getBytes(StandardCharsets.UTF_8));

        var comments = admin.get("/api/v1/issues/WID-1/comments");
        assertThat((String) comments.json("$[0].body")).isEqualTo("Same here, see WID-2.");
        assertThat((String) comments.json("$[0].author.username")).isEqualTo("carol");
        assertThat((String) comments.json("$[0].createdAt")).isEqualTo("2026-01-06T08:00:00Z");

        // Unknown people become placeholders; the importer's own account is not used for them.
        assertThat(jdbc.queryForList("select username from user_account where placeholder order by username",
                String.class)).containsExactly("bob", "carol");

        // Dependencies: #3 blocks #1.
        var links = admin.get("/api/v1/issues/WID-1/links");
        assertThat(links.body()).contains("WID-2").contains("is blocked by");

        // The original history, the pull request and the import itself.
        var history = admin.get("/api/v1/issues/WID-1/history");
        List<String> fieldsInOrder = history.json("$[*].items[*].field");
        assertThat(fieldsInOrder).containsSubsequence("labels", "remotelink", "status", "import");
        assertThat(history.body()).contains("\"fromText\":\"Crash\"", "\"toText\":\"Crash on start\"",
                "\"toText\":\"Forgejo acme/widgets#1\"", "\"toText\":\"needs-info\"");
        List<String> statusAuthors = history.json("$[?(@.items[0].field == 'status')].author.username");
        assertThat(statusAuthors).containsExactly("alice");

        // Nobody is mailed about imported issues, and the token is nowhere in the database.
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select options from import_job where id = ?", String.class, jobId))
                .doesNotContain(FakeForgejo.TOKEN);
        assertThat(jdbc.queryForObject("select count(*) from import_job_log where message like ?", Long.class,
                "%" + FakeForgejo.TOKEN + "%")).isZero();
        // The token goes to the API and the files of the server, and nowhere else.
        assertThat(forgejo.authorizations).allMatch(a -> a.endsWith("token " + FakeForgejo.TOKEN));
    }

    @Test
    void runningAgainOnlyAddsWhatIsNew() throws Exception {
        long first = jobId(start("WID"));
        assertThat(waitFor(first).get("status")).isEqualTo("SUCCEEDED");
        assertThat((Boolean) admin.get("/api/v1/admin/import/jobs/" + first).json("$.credentialsAvailable")).isTrue();

        Map<String, Object> three = issue(base, 103, 3, bob, "Document the start", "", "open");
        three.put("comments", 1);
        Map<String, Object> four = issue(base, 104, 4, carol, "New one", "Like #1", "open");
        forgejo.json("/repos/acme/widgets/issues", list(issueOne(), three, four));
        forgejo.json("/repos/acme/widgets/issues/3/comments",
                list(comment(base, 902, alice, "Done in the README.", "2026-02-01T10:00:00+01:00")));
        forgejo.json("/repos/acme/widgets/issues/4/timeline", list());
        forgejo.json("/repos/acme/widgets/issues/4/dependencies", list());

        // Retrying uses the token that is still in memory.
        var retry = admin.post("/api/v1/admin/import/jobs/" + first + "/retry", "{}");
        long second = jobId(retry);
        Map<String, Object> job = waitFor(second);
        assertThat(job.get("status")).as(job.toString()).isEqualTo("SUCCEEDED");
        assertThat(job).extractingByKey("counters").asString().contains("issuesImported=1", "comments=1");

        assertThat(jdbc.queryForObject("select count(*) from issue", Long.class)).isEqualTo(3);
        assertThat((String) admin.get("/api/v1/issues/WID-3").json("$.description")).isEqualTo("Like WID-1");
        assertThat((String) admin.get("/api/v1/issues/WID-2/comments").json("$[0].body"))
                .isEqualTo("Done in the README.");
        assertThat(jdbc.queryForObject("select count(*) from comment c join issue i on i.id = c.issue_id "
                + "where i.origin_key = 'acme/widgets#1'", Long.class)).isEqualTo(1);
    }

    private Map<String, Object> issueOne() {
        Map<String, Object> one = issue(base, 101, 1, alice, "Crash on start", "body", "closed");
        one.put("comments", 1);
        return one;
    }

    @Test
    void importsIntoAnExistingProjectAndChecksTheIssueType() throws Exception {
        String project = admin.post("/api/v1/projects", "{\"key\": \"OLD\", \"name\": \"Old\"}").json("$.key");
        createIssue(admin, project, "Task", "Was here first");
        long projectId = jdbc.queryForObject("select id from project where key = 'OLD'", Long.class);

        var started = admin.post("/api/v1/admin/import/forgejo/jobs", """
                {"url": "%s", "repository": "acme/widgets", "projectId": %d, "issueTypeId": %d,
                 "labels": {}, "users": {}, "matchUsernames": false}"""
                .formatted(base, projectId, typeId("Task")));
        long jobId = jobId(started);
        Map<String, Object> job = waitFor(jobId);
        assertThat(job.get("status")).as(job.toString()).isEqualTo("SUCCEEDED");
        assertThat((String) admin.get("/api/v1/issues/OLD-2").json("$.origin.key")).isEqualTo("acme/widgets#1");
        assertThat((List<String>) admin.get("/api/v1/issues/OLD-2").json("$.labels"))
                .containsExactly("kind/bug", "needs-info");
        assertThat((Boolean) admin.get("/api/v1/admin/import/jobs/" + jobId).json("$.credentialsAvailable"))
                .isFalse();

        var badType = admin.post("/api/v1/admin/import/forgejo/jobs", """
                {"url": "%s", "repository": "acme/widgets", "projectId": %d, "issueTypeId": %d}"""
                .formatted(base, projectId, typeId("Epic")));
        assertThat(badType.status()).isEqualTo(400);
        assertThat(badType.body()).contains("standard issue type");
    }

    @Test
    void onlyAdministratorsMayImport() {
        createUser(admin, "mallory", "mallory-password-1");
        ApiClient mallory = loginAs("mallory", "mallory-password-1");
        assertThat(mallory.get("/api/v1/admin/import/jobs").status()).isEqualTo(403);
        assertThat(mallory.post("/api/v1/admin/import/forgejo/connection",
                "{\"url\": \"%s\"}".formatted(base)).status()).isEqualTo(403);
    }
}
