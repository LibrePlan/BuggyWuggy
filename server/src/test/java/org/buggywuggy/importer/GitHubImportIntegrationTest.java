/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.buggywuggy.importer.FakeGitHub.event;
import static org.buggywuggy.importer.FakeGitHub.list;
import static org.buggywuggy.importer.FakeGitHub.map;
import static org.buggywuggy.importer.FakeGitHub.user;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubImportIntegrationTest extends IssueTestSupport {

    private static final String UUID = "6c1e1c5e-77d4-4c9b-9f3e-1d2b3c4d5e6f";

    private FakeGitHub github;
    private String base;
    private ApiClient admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from import_source");
        jdbc.update("delete from outbox_event");
        github = new FakeGitHub();
        base = github.url();
        admin = setupAndLoginAdmin();
        createUser(admin, "alice", "alice-password-123");

        Map<String, Object> alice = user(21, "alice");
        Map<String, Object> bob = user(22, "bob");
        Map<String, Object> carol = user(23, "carol");
        github.json("/user", map("login", "importer", "id", 1, "site_admin", true));
        github.json("/meta", map("installed_version", "3.14.2"));
        github.json("/users/alice", map("login", "alice", "id", 21, "name", "Alice Liddell",
                "email", "alice@example.org"));
        github.json("/users/bob", map("login", "bob", "id", 22, "name", "Bob Builder", "email", null));
        github.json("/repos/acme/widgets", map("id", 7, "full_name", "acme/widgets",
                "html_url", base + "/acme/widgets", "description", "Widgets", "has_issues", true,
                "open_issues_count", 3, "private", false, "archived", false));
        github.pages("/repos/acme/widgets/labels", list(map("name", "bug", "color", "d73a4a", "description", ""),
                map("name", "needs info", "color", "cccccc", "description", null)));
        Map<String, Object> v1 = map("number", 1, "title", "v1.0", "description", "First", "state", "closed",
                "due_on", "2026-03-01T08:00:00Z");
        github.pages("/repos/acme/widgets/milestones", list(v1));

        Map<String, Object> one = github.issue(101, 1, alice, "Crash on start",
                "Starting crashes, see " + base + "/acme/widgets/issues/3 and the fix in #2.\n\n"
                        + "![shot](" + base + "/user-attachments/assets/" + UUID + ")", "closed");
        one.put("type", map("id", 5, "name", "Bug"));
        one.put("labels", list(map("name", "needs info", "color", "cccccc")));
        one.put("milestone", v1);
        one.put("assignees", list(bob));
        one.put("state_reason", "not_planned");
        one.put("closed_at", "2026-01-09T11:00:00Z");
        one.put("comments", 1);
        one.put("sub_issues_summary", map("total", 1));
        one.put("issue_dependencies_summary", map("blocked_by", 1, "total_blocked_by", 1));
        Map<String, Object> pull = github.issue(102, 2, bob, "Fix crash", "Fixes #1", "closed");
        pull.put("pull_request", map("url", base + "/api/v3/repos/acme/widgets/pulls/2",
                "html_url", base + "/acme/widgets/pull/2"));
        pull.put("html_url", base + "/acme/widgets/pull/2");
        Map<String, Object> three = github.issue(103, 3, bob, "Document the start", "", "open");
        Map<String, Object> four = github.issue(104, 4, carol, "Check the logs", "Part of #1", "open");
        // Two pages: the importer follows the Link header.
        github.pages("/repos/acme/widgets/issues", list(one, pull), list(three, four));

        github.pages("/repos/acme/widgets/issues/1/timeline", list(
                event("labeled", alice, "2026-01-05T09:00:00Z", "label", map("name", "needs info")),
                map("event", "commented", "id", 901, "actor", carol, "user", carol, "body", "Same here, see #3.",
                        "created_at", "2026-01-06T08:00:00Z", "updated_at", "2026-01-06T08:00:00Z"),
                event("milestoned", bob, "2026-01-07T08:00:00Z", "milestone", map("title", "v1.0")),
                event("renamed", bob, "2026-01-07T08:00:01Z", "rename", map("from", "Crash", "to", "Crash on start")),
                event("cross-referenced", bob, "2026-01-08T08:00:00Z", "source", map("type", "issue", "issue", pull)),
                event("closed", alice, "2026-01-09T11:00:00Z", "state_reason", "not_planned")));
        github.pages("/repos/acme/widgets/issues/1/sub_issues", list(four));
        github.pages("/repos/acme/widgets/issues/1/dependencies/blocked_by", list(three));
        github.pages("/repos/acme/widgets/issues/3/timeline", list());
        github.pages("/repos/acme/widgets/issues/4/timeline", list());
        github.attachment(UUID, "PNG!".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        github.close();
    }

    private Map<String, Object> waitFor(long jobId) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Map<String, Object> job = admin.get("/api/v1/admin/import/jobs/" + jobId).json("$");
            if (!"RUNNING".equals(job.get("status"))) {
                return job;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("import did not finish");
    }

    @Test
    void needsATokenAndAnalysesTheRepository() {
        var noToken = admin.post("/api/v1/admin/import/github/connection", "{\"url\": \"%s\"}".formatted(base));
        assertThat(noToken.status()).isEqualTo(400);
        assertThat(noToken.body()).contains("access token");

        var connection = admin.post("/api/v1/admin/import/github/connection",
                "{\"url\": \"%s/api/v3\", \"token\": \"%s\"}".formatted(base, FakeGitHub.TOKEN));
        assertThat(connection.status()).as(connection.toString()).isEqualTo(200);
        assertThat((String) connection.json("$.url")).isEqualTo(base);
        assertThat((String) connection.json("$.login")).isEqualTo("importer");
        assertThat((String) connection.json("$.version")).isEqualTo("GitHub Enterprise Server 3.14.2");

        var repositories = admin.post("/api/v1/admin/import/github/repositories",
                "{\"url\": \"%s\", \"token\": \"%s\", \"query\": \"acme/widgets\"}".formatted(base, FakeGitHub.TOKEN));
        assertThat((String) repositories.json("$[0].fullName")).isEqualTo("acme/widgets");

        var analysis = admin.post("/api/v1/admin/import/github/analysis",
                "{\"url\": \"%s\", \"token\": \"%s\", \"repository\": \"acme/widgets\"}"
                        .formatted(base, FakeGitHub.TOKEN));
        assertThat(analysis.status()).as(analysis.toString()).isEqualTo(200);
        assertThat((Integer) analysis.json("$.issues")).isEqualTo(3); // the pull request is not an issue
        assertThat((Integer) analysis.json("$.openIssues")).isEqualTo(2);
        List<String> typeRule = analysis.json("$.labels[?(@.name == 'type:Bug')].suggestion.target");
        assertThat(typeRule).containsExactly("ISSUE_TYPE");
        List<String> matched = analysis.json("$.users[?(@.login == 'alice')].suggestion.username");
        assertThat(matched).containsExactly("alice"); // by the public e-mail address of the profile
        List<String> names = analysis.json("$.users[?(@.login == 'bob')].name");
        assertThat(names).containsExactly("Bob Builder");
    }

    @Test
    void importsIssuesWithSubIssuesDependenciesAndAttachmentsAndOnlyReads() throws Exception {
        github.rateLimitOnce("/repos/acme/widgets/milestones");
        var started = admin.post("/api/v1/admin/import/github/jobs", """
                {"url": "%s", "token": "%s", "repository": "acme/widgets",
                 "newProject": {"key": "WID", "name": "Widgets"}, "issueTypeId": %d,
                 "labels": {"type:Bug": {"target": "ISSUE_TYPE", "id": %d}}, "users": {}}"""
                .formatted(base, FakeGitHub.TOKEN, typeId("Task"), typeId("Bug")));
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        long jobId = ((Number) started.json("$.id")).longValue();
        Map<String, Object> job = waitFor(jobId);
        assertThat(job.get("status")).as(job.toString()).isEqualTo("SUCCEEDED");
        assertThat(job.get("statusText")).isNull();
        assertThat(job).extractingByKey("counters").asString()
                .contains("issuesTotal=3", "issuesImported=3", "comments=1", "attachments=1", "usersCreated=2");

        var one = admin.get("/api/v1/issues/WID-1");
        assertThat((String) one.json("$.origin.kind")).isEqualTo("GITHUB");
        assertThat((String) one.json("$.origin.key")).isEqualTo("acme/widgets#1");
        assertThat((String) one.json("$.issueType.name")).isEqualTo("Bug");
        assertThat((String) one.json("$.status.name")).isEqualTo("Closed");
        assertThat((String) one.json("$.resolution.name")).isEqualTo("Won't Fix");
        assertThat((List<String>) one.json("$.labels")).containsExactly("needs-info");
        assertThat((String) one.json("$.fixVersions[0].name")).isEqualTo("v1.0");
        assertThat((String) one.json("$.reporter.username")).isEqualTo("alice");
        assertThat((String) one.json("$.assignee.displayName")).isEqualTo("Bob Builder");
        assertThat((String) one.json("$.remoteLinks[0].url")).isEqualTo(base + "/acme/widgets/pull/2");

        long attachmentId = jdbc.queryForObject("""
                select a.id from attachment a join issue i on i.id = a.issue_id
                where i.origin_key = 'acme/widgets#1'""", Long.class);
        assertThat(jdbc.queryForObject("select file_name from attachment where id = ?", String.class, attachmentId))
                .isEqualTo(UUID + ".png");
        assertThat((String) one.json("$.description"))
                .contains("see WID-2 and the fix in [#2](" + base + "/acme/widgets/issues/2)")
                .contains("![shot](/api/v1/attachments/" + attachmentId + "/content)");
        assertThat(admin.download("/api/v1/attachments/" + attachmentId + "/content").body())
                .isEqualTo("PNG!".getBytes(StandardCharsets.UTF_8));
        assertThat((String) admin.get("/api/v1/issues/WID-1/comments").json("$[0].body"))
                .isEqualTo("Same here, see WID-2.");

        // A sub-issue of a standard issue becomes a sub-task; "blocked by" becomes a link.
        var four = admin.get("/api/v1/issues/WID-3");
        assertThat((String) four.json("$.issueType.name")).isEqualTo("Sub-task");
        assertThat((String) four.json("$.parent.key")).isEqualTo("WID-1");
        assertThat(admin.get("/api/v1/issues/WID-1/links").body()).contains("WID-2").contains("is blocked by");

        var history = admin.get("/api/v1/issues/WID-1/history");
        assertThat(history.body()).contains("\"toText\":\"GitHub acme/widgets#1\"", "\"fromText\":\"Crash\"",
                "\"toText\":\"Won't Fix\"");

        // It waited for the rate limit, and said so.
        assertThat(jdbc.queryForObject("select count(*) from import_job_log where job_id = ? and message like ?",
                Long.class, jobId, "Waiting until %rate limit%")).isEqualTo(1);
        var log = admin.get("/api/v1/admin/import/jobs/" + jobId + "/log.txt");
        assertThat(log.status()).isEqualTo(200);
        assertThat(log.body()).contains("INFO Waiting until", "INFO Done: 3 issues imported");

        // Only reads; the token never leaves the server, nor is it stored.
        assertThat(github.methods).isNotEmpty().containsOnly("GET");
        assertThat(github.storageAuthorizations).isNotEmpty().containsOnly("null");
        assertThat(jdbc.queryForObject("select options from import_job where id = ?", String.class, jobId))
                .doesNotContain(FakeGitHub.TOKEN);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class)).isZero();
    }
}
