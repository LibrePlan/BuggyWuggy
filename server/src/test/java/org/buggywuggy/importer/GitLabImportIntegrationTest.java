/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.buggywuggy.importer.FakeGitLab.list;
import static org.buggywuggy.importer.FakeGitLab.map;
import static org.buggywuggy.importer.FakeGitLab.note;
import static org.buggywuggy.importer.FakeGitLab.user;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitLabImportIntegrationTest extends IssueTestSupport {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String PROJECT = "/projects/acme%2Ftools%2Fwidgets";

    private FakeGitLab gitlab;
    private String base;
    private ApiClient admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from import_source");
        jdbc.update("delete from outbox_event");
        gitlab = new FakeGitLab();
        base = gitlab.url();
        admin = setupAndLoginAdmin();
        createUser(admin, "alice", "alice-password-123");

        Map<String, Object> alice = user(21, "alice", "Alice");
        Map<String, Object> bob = user(22, "bob", "Bob Builder");
        Map<String, Object> carol = user(23, "carol", "Carol");
        gitlab.json("/version", map("version", "17.5.0-ee", "revision", "abc"));
        gitlab.json("/user", map("id", 1, "username", "importer", "name", "Importer", "is_admin", true));
        gitlab.json("/users/21", map("id", 21, "username", "alice", "name", "Alice Liddell",
                "public_email", "alice@example.org"));
        gitlab.json("/users/22", map("id", 22, "username", "bob", "name", "Bob Builder", "public_email", ""));
        gitlab.json("/users", list(user(24, "dave", "Dave")));
        Map<String, Object> project = map("id", 7, "path_with_namespace", "acme/tools/widgets",
                "web_url", base + "/acme/tools/widgets", "description", "Widgets", "visibility", "private",
                "issues_enabled", true, "issues_access_level", "enabled", "open_issues_count", 2, "archived", false);
        gitlab.json(PROJECT, project);
        gitlab.pages("/projects", list(project));
        gitlab.pages("/projects/7/labels", list(map("name", "bug", "color", "#d9534f", "description", null),
                map("name", "needs info", "color", "#cccccc", "description", "")));
        Map<String, Object> v1 = map("id", 3, "iid", 1, "title", "v1.0", "description", "First", "state", "closed",
                "due_date", "2026-03-01");
        gitlab.pages("/projects/7/milestones", list(v1));

        Map<String, Object> one = gitlab.issue(101, 1, alice, "Crash on start",
                "Starting crashes, see #3 and " + base + "/acme/tools/widgets/-/issues/4, fixed in !5.\n\n"
                        + "![shot](/uploads/" + SECRET + "/shot.png)", "closed");
        one.put("labels", list("bug", "needs info"));
        one.put("milestone", v1);
        one.put("assignees", list(bob));
        one.put("closed_at", "2026-01-09T11:00:00.000Z");
        one.put("user_notes_count", 1);
        one.put("due_date", "2026-02-01");
        one.put("issue_type", "incident");
        Map<String, Object> three = gitlab.issue(103, 3, bob, "Document the start", "", "opened");
        Map<String, Object> four = gitlab.issue(104, 4, carol, "Check the logs", "Part of #1", "opened");
        four.put("confidential", true);
        gitlab.pages("/projects/7/issues", list(one), list(three, four));

        Map<String, Object> internal = note(904, bob, "Only for the team", false, "2026-01-06T10:00:00.000Z");
        internal.put("internal", true);
        gitlab.pages("/projects/7/issues/1/notes", list(
                note(901, carol, "Same here, see #3.", false, "2026-01-06T08:00:00.000Z"),
                internal,
                note(902, bob, "changed title from **Crash** to **Crash{+ on start+}**", true,
                        "2026-01-07T08:00:00.000Z"),
                note(903, bob, "assigned to @bob and unassigned @dave", true, "2026-01-07T08:00:01.000Z"),
                note(905, alice, "changed due date to February 1, 2026", true, "2026-01-07T09:00:00.000Z")));
        gitlab.pages("/projects/7/issues/1/resource_label_events", list(
                map("id", 1, "user", alice, "created_at", "2026-01-05T09:00:00.000Z",
                        "label", map("id", 1, "name", "bug"), "action", "add"),
                map("id", 2, "user", bob, "created_at", "2026-01-07T08:00:02.000Z",
                        "label", map("id", 2, "name", "needs info"), "action", "add")));
        gitlab.pages("/projects/7/issues/1/resource_milestone_events", list(map("id", 1, "user", bob,
                "created_at", "2026-01-07T08:00:02.000Z", "milestone", v1, "action", "add")));
        gitlab.pages("/projects/7/issues/1/resource_state_events", list(map("id", 1, "user", alice,
                "created_at", "2026-01-09T11:00:00.000Z", "state", "closed")));
        gitlab.pages("/projects/7/issues/1/related_merge_requests", list(map("iid", 5, "title", "Fix crash",
                "web_url", base + "/acme/tools/widgets/-/merge_requests/5", "author", bob,
                "created_at", "2026-01-08T08:00:00.000Z", "references", map("full", "acme/tools/widgets!5"))));
        gitlab.pages("/projects/7/issues/1/links", list(
                map("iid", 3, "project_id", 7, "link_type", "is_blocked_by",
                        "link_created_at", "2026-01-07T12:00:00.000Z"),
                map("iid", 4, "project_id", 7, "link_type", "relates_to")));
        for (int iid : new int[] {3, 4}) {
            String issue = "/projects/7/issues/" + iid;
            gitlab.pages(issue + "/notes", list());
            gitlab.pages(issue + "/resource_label_events", list());
            gitlab.pages(issue + "/resource_milestone_events", list());
            gitlab.pages(issue + "/resource_state_events", list());
        }
        gitlab.pages("/projects/7/issues/3/links", list(map("iid", 1, "project_id", 7, "link_type", "blocks")));
        gitlab.pages("/projects/7/issues/4/links", list(map("iid", 1, "project_id", 7, "link_type", "relates_to")));
        // An older GitLab without the uploads API: the file comes from the project's web address.
        gitlab.file("/acme/tools/widgets/uploads/" + SECRET + "/shot.png", "PNG!".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        gitlab.close();
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
    void connectsWithAndWithoutTokenAndAnalysesAProjectInASubgroup() {
        var anonymous = admin.post("/api/v1/admin/import/gitlab/connection", "{\"url\": \"%s\"}".formatted(base));
        assertThat(anonymous.status()).as(anonymous.toString()).isEqualTo(200);
        assertThat((Object) anonymous.json("$.login")).isNull();

        var connection = admin.post("/api/v1/admin/import/gitlab/connection",
                "{\"url\": \"%s/api/v4/\", \"token\": \"%s\"}".formatted(base, FakeGitLab.TOKEN));
        assertThat(connection.status()).as(connection.toString()).isEqualTo(200);
        assertThat((String) connection.json("$.url")).isEqualTo(base);
        assertThat((String) connection.json("$.version")).isEqualTo("17.5.0-ee");
        assertThat((String) connection.json("$.login")).isEqualTo("importer");

        var repositories = admin.post("/api/v1/admin/import/gitlab/repositories",
                "{\"url\": \"%s\", \"token\": \"%s\", \"query\": \"acme/tools/widgets\"}"
                        .formatted(base, FakeGitLab.TOKEN));
        assertThat((String) repositories.json("$[0].fullName")).isEqualTo("acme/tools/widgets");
        assertThat((Boolean) repositories.json("$[0].isPrivate")).isTrue();

        var analysis = admin.post("/api/v1/admin/import/gitlab/analysis",
                "{\"url\": \"%s\", \"token\": \"%s\", \"repository\": \"acme/tools/widgets\"}"
                        .formatted(base, FakeGitLab.TOKEN));
        assertThat(analysis.status()).as(analysis.toString()).isEqualTo(200);
        assertThat((Integer) analysis.json("$.issues")).isEqualTo(3);
        assertThat((Integer) analysis.json("$.comments")).isEqualTo(1);
        List<String> colors = analysis.json("$.labels[?(@.name == 'bug')].color");
        assertThat(colors).containsExactly("d9534f");
        List<String> incident = analysis.json("$.labels[?(@.name == 'type:Incident')].suggestion.target");
        assertThat(incident).containsExactly("IGNORE");
        List<String> matched = analysis.json("$.users[?(@.login == 'alice')].suggestion.username");
        assertThat(matched).containsExactly("alice"); // by the public e-mail address
    }

    @Test
    void saysSoWhenAServerShowsLabelsAndCommentsOnlyToLoggedInUsers() {
        // As gitlab.com: projects and issues for anyone, labels and comments only with a login.
        gitlab.loginOnly.add("/projects/7/labels");
        gitlab.loginOnly.add("/projects/7/issues/1/notes");
        var anonymous = admin.post("/api/v1/admin/import/gitlab/connection", "{\"url\": \"%s\"}".formatted(base));
        assertThat(anonymous.status()).as(anonymous.toString()).isEqualTo(400);
        assertThat(anonymous.body()).contains("only lets logged-in users read labels, comments and history")
                .doesNotContain("refused");

        // Found halfway (the connection was not tested): the same message, with what it could not read.
        var analysis = admin.post("/api/v1/admin/import/gitlab/analysis",
                "{\"url\": \"%s\", \"repository\": \"acme/tools/widgets\"}".formatted(base));
        assertThat(analysis.status()).as(analysis.toString()).isEqualTo(400);
        assertThat(analysis.body()).contains("only lets logged-in users read", "/projects/7/");

        // With a token all is well.
        var connection = admin.post("/api/v1/admin/import/gitlab/connection",
                "{\"url\": \"%s\", \"token\": \"%s\"}".formatted(base, FakeGitLab.TOKEN));
        assertThat(connection.status()).as(connection.toString()).isEqualTo(200);
    }

    @Test
    void importsIssuesWithHistoryLinksAndUploadsAndOnlyReads() throws Exception {
        gitlab.rateLimitOnce("/projects/7/milestones");
        var started = admin.post("/api/v1/admin/import/gitlab/jobs", """
                {"url": "%s", "token": "%s", "repository": "acme/tools/widgets",
                 "newProject": {"key": "WID", "name": "Widgets"}, "issueTypeId": %d,
                 "labels": {"bug": {"target": "ISSUE_TYPE", "id": %d}, "type:Incident": {"target": "IGNORE"}},
                 "users": {}}"""
                .formatted(base, FakeGitLab.TOKEN, typeId("Task"), typeId("Bug")));
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        long jobId = ((Number) started.json("$.id")).longValue();
        Map<String, Object> job = waitFor(jobId);
        assertThat(job.get("status")).as(job.toString()).isEqualTo("SUCCEEDED");
        assertThat(job).extractingByKey("counters").asString()
                .contains("issuesTotal=3", "issuesImported=3", "comments=1", "attachments=1");

        var one = admin.get("/api/v1/issues/WID-1");
        assertThat((String) one.json("$.origin.kind")).isEqualTo("GITLAB");
        assertThat((String) one.json("$.origin.key")).isEqualTo("acme/tools/widgets#1");
        assertThat((String) one.json("$.issueType.name")).isEqualTo("Bug");
        assertThat((String) one.json("$.status.name")).isEqualTo("Closed");
        assertThat((List<String>) one.json("$.labels")).containsExactly("needs-info");
        assertThat((String) one.json("$.dueDate")).isEqualTo("2026-02-01");
        assertThat((String) one.json("$.fixVersions[0].name")).isEqualTo("v1.0");
        assertThat((String) one.json("$.reporter.username")).isEqualTo("alice");
        assertThat((String) one.json("$.assignee.displayName")).isEqualTo("Bob Builder");
        assertThat((String) one.json("$.remoteLinks[0].url"))
                .isEqualTo(base + "/acme/tools/widgets/-/merge_requests/5");

        long attachmentId = jdbc.queryForObject("""
                select a.id from attachment a join issue i on i.id = a.issue_id
                where i.origin_key = 'acme/tools/widgets#1'""", Long.class);
        assertThat((String) one.json("$.description"))
                .contains("see WID-2 and WID-3, fixed in !5")
                .contains("![shot](/api/v1/attachments/" + attachmentId + "/content)");
        assertThat(admin.download("/api/v1/attachments/" + attachmentId + "/content").body())
                .isEqualTo("PNG!".getBytes(StandardCharsets.UTF_8));

        // Internal notes are not imported.
        var comments = admin.get("/api/v1/issues/WID-1/comments");
        assertThat((List<Object>) comments.json("$")).hasSize(1);
        assertThat((String) comments.json("$[0].body")).isEqualTo("Same here, see WID-2.");

        assertThat((List<String>) admin.get("/api/v1/issues/WID-3").json("$.labels")).contains("confidential");
        var links = admin.get("/api/v1/issues/WID-1/links").body();
        assertThat(links).contains("is blocked by").contains("WID-2").contains("relates to").contains("WID-3");
        // "Relates" is listed by both issues, but linked once.
        assertThat(jdbc.queryForObject("select count(*) from issue_link", Long.class)).isEqualTo(2);

        var history = admin.get("/api/v1/issues/WID-1/history");
        assertThat(history.body()).contains("\"fromText\":\"Crash\"", "\"toText\":\"Crash on start\"",
                "\"toText\":\"GitLab acme/tools/widgets#1\"", "\"toText\":\"2026-02-01\"");
        List<String> assignees = history.json("$[*].items[?(@.field == 'assignee')].fromText");
        assertThat(assignees).containsExactly("Dave");

        assertThat(jdbc.queryForObject("select count(*) from import_job_log where job_id = ? and message like ?",
                Long.class, jobId, "Waiting until %rate limit%")).isEqualTo(1);
        assertThat(gitlab.methods).isNotEmpty().containsOnly("GET");
        assertThat(gitlab.authorizations).allMatch(a -> a.endsWith("Bearer " + FakeGitLab.TOKEN));
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class)).isZero();
    }
}
