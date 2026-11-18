/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.buggywuggy.importer.FakeBugzilla.list;
import static org.buggywuggy.importer.FakeBugzilla.map;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BugzillaImportIntegrationTest extends IssueTestSupport {

    private FakeBugzilla bugzilla;
    private String base;
    private ApiClient admin;

    private static Map<String, Object> bug(long id, String summary, String status, String resolution, String creator,
            Map<String, Object> creatorDetail) {
        Map<String, Object> bug = map("id", id, "product", "Web Shop", "summary", summary, "status", status,
                "resolution", resolution, "is_open", resolution.isEmpty(), "severity", "normal", "priority", "--",
                "component", "Backend", "target_milestone", "---", "creator", creator,
                "assigned_to", "nobody@example.org", "creation_time", "2026-01-05T09:00:00Z",
                "last_change_time", "2026-01-09T11:00:00Z", "keywords", list(), "groups", list(),
                "depends_on", list(), "blocks", list(), "dupe_of", null, "see_also", list());
        if (creatorDetail != null) {
            bug.put("creator_detail", creatorDetail);
        }
        return bug;
    }

    private static Map<String, Object> comment(long id, long count, String text, String creator, boolean isPrivate) {
        return map("id", id, "count", count, "text", text, "creator", creator,
                "creation_time", "2026-01-0" + Math.min(9, 5 + count) + "T10:00:00Z", "is_private", isPrivate);
    }

    private void comments(long bug, Object... comments) {
        bugzilla.json("/bug/" + bug + "/comment", map("bugs", map(String.valueOf(bug), map("comments",
                list(comments))), "comments", map()));
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from import_source");
        jdbc.update("delete from outbox_event");
        bugzilla = new FakeBugzilla();
        base = bugzilla.url();
        admin = setupAndLoginAdmin();
        // alice@example.org: matched by e-mail address.
        createUser(admin, "alice", "alice-password-123");

        bugzilla.json("/version", map("version", "5.0.4"));
        bugzilla.json("/whoami", map("id", 1, "name", "importer@example.org", "real_name", "Im Porter"));
        bugzilla.json("/field/bug/bug_status", map("fields", list(map("name", "bug_status", "values", list(
                map("name", "NEW", "is_open", true), map("name", "ASSIGNED", "is_open", true),
                map("name", "RESOLVED", "is_open", false), map("name", "VERIFIED", "is_open", false))))));
        bugzilla.product(map("id", 1, "name", "Web Shop", "description", "The shop", "is_active", true,
                "components", list(map("name", "Backend", "description", "Server side"),
                        map("name", "Frontend", "description", "Browser side")),
                "milestones", list(map("name", "---", "is_active", true), map("name", "1.0", "is_active", false))));
        bugzilla.product(map("id", 2, "name", "Operations", "description", "", "is_active", true));
        bugzilla.user("dave@example.org", "Dave Example");

        Map<String, Object> alice = map("id", 3, "name", "alice@example.org", "real_name", "Alice Liddell");
        Map<String, Object> bob = map("id", 4, "name", "bob@example.org", "real_name", "Bob Builder");

        Map<String, Object> one = bug(11, "Crash on start", "RESOLVED", "FIXED", "alice@example.org", alice);
        one.putAll(map("severity", "critical", "priority", "P1", "type", "defect", "target_milestone", "1.0",
                "assigned_to", "dave@example.org", "keywords", list("regression"), "depends_on", list(12, 99),
                "see_also", list(base + "/show_bug.cgi?id=12"), "deadline", "2026-02-01"));
        Map<String, Object> two = bug(12, "Show the basket", "NEW", "", "bob@example.org", bob);
        two.putAll(map("severity", "enhancement", "component", "Frontend", "blocks", list(11),
                "see_also", list(base + "/show_bug.cgi?id=11")));
        Map<String, Object> three = bug(13, "Crashes too", "RESOLVED", "DUPLICATE", "bob@example.org", bob);
        three.putAll(map("type", "defect", "dupe_of", 11, "groups", list("security")));
        Map<String, Object> other = bug(14, "In another product", "NEW", "", "bob@example.org", bob);
        other.put("product", "Operations");
        bugzilla.bug(one);
        bugzilla.bug(two);
        bugzilla.bug(three);
        bugzilla.bug(other);

        comments(11,
                comment(100, 0, """
                        Starting crashes, see bug 12 and https://docs.example/x_y.
                        # not a heading
                        *not bold* and <b>no html</b>
                            at Foo.bar(Foo.java:1)
                        Screenshot in attachment 21.""", "alice@example.org", false),
                comment(101, 1, "(In reply to Alice Liddell from comment #0)\n> Starting crashes\nSame here, see Bug #12.",
                        "bob@example.org", false),
                comment(102, 2, "Only for insiders", "bob@example.org", true),
                comment(103, 3, "Created attachment 21\nscreenshot", "alice@example.org", false));
        comments(12, comment(110, 0, "Please show it.", "bob@example.org", false));
        comments(13, comment(120, 0, "", "bob@example.org", false));

        bugzilla.json("/bug/11/history", map("bugs", list(map("id", 11, "history", list(
                map("when", "2026-01-07T08:00:00Z", "who", "alice@example.org", "changes", list(
                        map("field_name", "summary", "removed", "Crash", "added", "Crash on start"),
                        map("field_name", "assigned_to", "removed", "bob@example.org", "added", "dave@example.org"),
                        map("field_name", "priority", "removed", "P3", "added", "P1"))),
                map("when", "2026-01-09T11:00:00Z", "who", "alice@example.org", "changes", list(
                        map("field_name", "status", "removed", "NEW", "added", "RESOLVED"),
                        map("field_name", "resolution", "removed", "", "added", "FIXED"))))))));
        bugzilla.json("/bug/11/attachment", map("bugs", map("11", list(
                map("id", 21, "file_name", "shot.png", "size", 4, "content_type", "image/png",
                        "creation_time", "2026-01-08T10:00:00Z", "creator", "alice@example.org",
                        "is_private", false, "is_obsolete", false),
                map("id", 22, "file_name", "old.png", "size", 4, "content_type", "image/png",
                        "creation_time", "2026-01-08T09:00:00Z", "creator", "alice@example.org",
                        "is_private", false, "is_obsolete", true)))));
        bugzilla.json("/bug/attachment/21", map("attachments", map("21", map("id", 21, "content_type", "image/png",
                "data", Base64.getEncoder().encodeToString("PNG!".getBytes(StandardCharsets.UTF_8))))));
        bugzilla.json("/bug/99", map("bugs", list(map("id", 99, "product", "Operations"))));
    }

    @AfterEach
    void tearDown() {
        bugzilla.close();
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
    void connectsSearchesAndSuggestsAMapping() {
        var anonymous = admin.post("/api/v1/admin/import/bugzilla/connection", "{\"url\": \"%s\"}".formatted(base));
        assertThat(anonymous.status()).as(anonymous.toString()).isEqualTo(200);
        assertThat((String) anonymous.json("$.version")).isEqualTo("Bugzilla 5.0.4");
        assertThat((Object) anonymous.json("$.login")).isNull();

        var connection = admin.post("/api/v1/admin/import/bugzilla/connection",
                "{\"url\": \"%s/show_bug.cgi?id=11\", \"token\": \"%s\"}".formatted(base, FakeBugzilla.KEY));
        assertThat(connection.status()).as(connection.toString()).isEqualTo(200);
        assertThat((String) connection.json("$.url")).isEqualTo(base);
        assertThat((String) connection.json("$.login")).isEqualTo("importer@example.org");

        var wrongKey = admin.post("/api/v1/admin/import/bugzilla/connection",
                "{\"url\": \"%s\", \"token\": \"wrong\"}".formatted(base));
        assertThat(wrongKey.status()).isEqualTo(400);

        var search = admin.post("/api/v1/admin/import/bugzilla/repositories",
                "{\"url\": \"%s\", \"token\": \"%s\", \"query\": \"shop\"}".formatted(base, FakeBugzilla.KEY));
        assertThat((List<String>) search.json("$[*].fullName")).containsExactly("Web Shop");
        assertThat((String) search.json("$[0].description")).isEqualTo("The shop");

        var analysis = admin.post("/api/v1/admin/import/bugzilla/analysis",
                "{\"url\": \"%s\", \"token\": \"%s\", \"repository\": \"Web Shop\"}".formatted(base, FakeBugzilla.KEY));
        assertThat(analysis.status()).as(analysis.toString()).isEqualTo(200);
        assertThat((Integer) analysis.json("$.issues")).isEqualTo(3);
        assertThat((Integer) analysis.json("$.openIssues")).isEqualTo(1);
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'type:defect')].suggestion.target"))
                .containsExactly("ISSUE_TYPE");
        assertThat((List<Integer>) analysis.json("$.labels[?(@.name == 'type:enhancement')].suggestion.id"))
                .containsExactly((int) typeId("Feature"));
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'priority/P1')].suggestion.target"))
                .containsExactly("PRIORITY");
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'component/Backend')].suggestion.target"))
                .containsExactly("COMPONENT");
        assertThat((List<String>) analysis.json("$.users[?(@.login == 'alice')].suggestion.username"))
                .containsExactly("alice"); // by e-mail address
        // Paged: the fake gives two bugs per page.
        assertThat(bugzilla.requests).anyMatch(r -> r.startsWith("/bugzilla/rest.cgi/bug ")
                && r.contains("product=Web Shop") && r.contains("offset=2"));
    }

    @Test
    void importsBugsWithCommentsHistoryDependenciesAndAttachmentsAndOnlyReads() throws Exception {
        var started = admin.post("/api/v1/admin/import/bugzilla/jobs", """
                {"url": "%s", "token": "%s", "repository": "Web Shop",
                 "newProject": {"key": "SHOP", "name": "Shop"}, "issueTypeId": %d,
                 "labels": {"type:defect": {"target": "ISSUE_TYPE", "id": %d},
                            "type:enhancement": {"target": "ISSUE_TYPE", "id": %d},
                            "priority/P1": {"target": "PRIORITY", "id": %d},
                            "severity/normal": {"target": "IGNORE"},
                            "severity/critical": {"target": "LABEL"},
                            "component/Backend": {"target": "COMPONENT"},
                            "component/Frontend": {"target": "COMPONENT"}},
                 "users": {}}"""
                .formatted(base, FakeBugzilla.KEY, typeId("Task"), typeId("Bug"), typeId("Feature"),
                        jdbc.queryForObject("select id from priority where name = 'Highest'", Long.class)));
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        Map<String, Object> job = waitFor(((Number) started.json("$.id")).longValue());
        assertThat(job.get("status")).as(job.toString()).isEqualTo("SUCCEEDED");
        assertThat(job).extractingByKey("counters").asString()
                .contains("issuesTotal=3", "issuesImported=3", "comments=2", "attachments=1");

        var one = admin.get("/api/v1/issues/SHOP-1");
        assertThat((String) one.json("$.origin.kind")).isEqualTo("BUGZILLA");
        assertThat((String) one.json("$.origin.key")).isEqualTo("bug 11");
        assertThat((String) one.json("$.origin.url")).isEqualTo(base + "/show_bug.cgi?id=11");
        assertThat((String) one.json("$.issueType.name")).isEqualTo("Bug");
        assertThat((String) one.json("$.priority.name")).isEqualTo("Highest");
        assertThat((String) one.json("$.resolution.name")).isNotEqualTo("Duplicate");
        assertThat((List<String>) one.json("$.components[*].name")).containsExactly("Backend");
        assertThat((List<String>) one.json("$.labels")).contains("regression", "severity/critical");
        assertThat((String) one.json("$.fixVersions[0].name")).isEqualTo("1.0");
        assertThat((String) one.json("$.dueDate")).isEqualTo("2026-02-01");
        assertThat((String) one.json("$.reporter.username")).isEqualTo("alice");
        assertThat((String) one.json("$.assignee.displayName")).isEqualTo("Dave Example");
        assertThat((String) one.json("$.resolvedAt")).startsWith("2026-01-09T11:00");

        long attachmentId = jdbc.queryForObject("""
                select a.id from attachment a join issue i on i.id = a.issue_id
                where i.origin_key = 'bug 11'""", Long.class);
        String link = "/api/v1/attachments/" + attachmentId + "/content";
        // Plain text stays plain: no heading, no emphasis, no HTML; indented lines are code.
        assertThat((String) one.json("$.description")).isEqualTo("""
                Starting crashes, see SHOP-2 and https://docs.example/x_y.
                \\# not a heading
                \\*not bold\\* and \\<b\\>no html\\</b\\>
                    at Foo.bar(Foo.java:1)
                Screenshot in [attachment 21](%s).""".formatted(link));
        assertThat(admin.download(link).body()).isEqualTo("PNG!".getBytes(StandardCharsets.UTF_8));

        // The private comment is left out; replies keep their quote.
        var comments = admin.get("/api/v1/issues/SHOP-1/comments");
        assertThat((List<String>) comments.json("$[*].body")).containsExactly(
                "(In reply to Alice Liddell from comment #0)\n> Starting crashes\nSame here, see SHOP-2.",
                "Created [attachment 21](" + link + ")\nscreenshot");
        assertThat((String) comments.json("$[0].author.displayName")).isEqualTo("Bob Builder");

        // 12 blocks 11 (11 depends on it), and they see each other; 13 is a duplicate of 11;
        // 99 is in another product that was not imported.
        assertThat(admin.get("/api/v1/issues/SHOP-1/links").body())
                .contains("is blocked by", "relates to", "is duplicated by", "SHOP-2", "SHOP-3");
        assertThat(jdbc.queryForObject("select count(*) from issue_link", Long.class)).isEqualTo(3);
        assertThat(bugzilla.requests).anyMatch(r -> r.startsWith("/bugzilla/rest.cgi/bug/99 "));

        var two = admin.get("/api/v1/issues/SHOP-2");
        assertThat((String) two.json("$.issueType.name")).isEqualTo("Feature");
        assertThat((String) two.json("$.description")).isEqualTo("Please show it.");
        var three = admin.get("/api/v1/issues/SHOP-3");
        assertThat((String) three.json("$.resolution.name")).isEqualTo("Duplicate");
        assertThat((List<String>) three.json("$.labels")).contains("restricted");

        var history = admin.get("/api/v1/issues/SHOP-1/history");
        assertThat(history.body()).contains("\"fromText\":\"Crash\"", "\"toText\":\"Crash on start\"",
                "\"toText\":\"Bugzilla bug 11\"");
        List<String> assignees = history.json("$[*].items[?(@.field == 'assignee')].fromText");
        assertThat(assignees).containsExactly("Bob Builder");
        List<String> priorities = history.json("$[*].items[?(@.field == 'priority')].toText");
        assertThat(priorities).containsExactly("Highest");

        assertThat(bugzilla.methods).isNotEmpty().containsOnly("GET");
        assertThat(bugzilla.requests).allMatch(r -> !r.contains("/rest.cgi/") || r.endsWith(FakeBugzilla.KEY));
        assertThat(bugzilla.otherCredentials).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class)).isZero();
    }
}
