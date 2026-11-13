/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.buggywuggy.importer.FakeRedmine.issue;
import static org.buggywuggy.importer.FakeRedmine.list;
import static org.buggywuggy.importer.FakeRedmine.map;
import static org.buggywuggy.importer.FakeRedmine.named;
import static org.buggywuggy.importer.FakeRedmine.relation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RedmineImportIntegrationTest extends IssueTestSupport {

    private FakeRedmine redmine;
    private String base;
    private ApiClient admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from import_source");
        jdbc.update("delete from outbox_event");
        redmine = new FakeRedmine();
        base = redmine.url();
        admin = setupAndLoginAdmin();
        createUser(admin, "alice", "alice-password-123");

        Map<String, Object> alice = named(3, "Alice Liddell");
        Map<String, Object> bob = named(4, "Bob Builder");
        Map<String, Object> web = map("id", 1, "name", "Web shop", "identifier", "web", "is_public", true,
                "status", 1);
        redmine.pages("/projects.json", "projects", list(web,
                map("id", 2, "name", "Web shop API", "identifier", "web-api", "is_public", false),
                map("id", 3, "name", "Operations", "identifier", "ops", "is_public", true)));
        Map<String, Object> withModules = new java.util.LinkedHashMap<>(web);
        withModules.put("enabled_modules", list(map("id", 1, "name", "issue_tracking"), map("id", 2, "name", "wiki")));
        redmine.json("/projects/web.json", map("project", withModules));
        redmine.json("/projects/3.json", map("project", map("id", 3, "name", "Operations", "identifier", "ops")));
        redmine.json("/users/current.json", map("user", map("id", 1, "login", "importer", "firstname", "Im",
                "lastname", "Porter", "admin", true, "api_key", FakeRedmine.KEY)));
        redmine.json("/users/3.json", map("user", map("id", 3, "login", "alice.l", "firstname", "Alice",
                "lastname", "Liddell", "mail", "alice@example.org")));
        redmine.json("/users/5.json", map("user", map("id", 5, "login", "dave", "firstname", "Dave",
                "lastname", "")));
        // Bob's account page is not visible (only administrators see all users).
        redmine.json("/issue_statuses.json", map("issue_statuses", list(
                map("id", 1, "name", "New", "is_closed", false),
                map("id", 5, "name", "Closed", "is_closed", true),
                map("id", 6, "name", "Rejected", "is_closed", true))));
        redmine.json("/trackers.json", map("trackers", list(named(1, "Bug"), named(2, "Feature"),
                named(3, "Support"))));
        redmine.json("/enumerations/issue_priorities.json", map("issue_priorities", list(named(2, "Normal"),
                named(3, "High"))));
        redmine.json("/projects/web/issue_categories.json", map("issue_categories", list(named(1, "Backend"))));
        redmine.json("/projects/web/versions.json", map("versions", list(map("id", 1, "name", "1.0",
                "description", "First", "status", "closed", "due_date", "2026-03-01", "project", named(1, "Web shop")))));

        Map<String, Object> one = issue(11, 1, "Bug", 5, "Crash on start", alice);
        one.put("description", """
                h2. Crash
                Starting crashes, see #12 and "the docs":https://docs.example/x.
                * *bold* and @code@
                !shot.png!
                <pre><code class="java">
                int *x* = 1;
                </code></pre>""");
        one.put("priority", named(3, "High"));
        one.put("category", named(1, "Backend"));
        one.put("fixed_version", named(1, "1.0"));
        one.put("assigned_to", named(5, "Dave"));
        one.put("due_date", "2026-02-01");
        one.put("closed_on", "2026-01-09T11:00:00Z");
        one.put("attachments", list(map("id", 21, "filename", "shot.png", "filesize", 4, "content_type", "image/png",
                "content_url", "https://elsewhere.example/attachments/download/21/shot.png", "author", alice,
                "created_on", "2026-01-05T09:00:00Z")));
        one.put("relations", list(relation(1, 11, 12, "blocks"), relation(2, 13, 11, "duplicates"),
                relation(3, 11, 99, "relates")));
        Map<String, Object> two = issue(12, 1, "Feature", 1, "Show the basket", bob);
        two.put("relations", list(relation(1, 11, 12, "blocks")));
        Map<String, Object> three = issue(13, 1, "Support", 5, "Crashes too", bob);
        three.put("parent", map("id", 11));
        three.put("is_private", true);
        three.put("relations", list(relation(2, 13, 11, "duplicates")));
        Map<String, Object> sub = issue(14, 2, "Bug", 1, "In the subproject", bob);
        redmine.pages("/issues.json", "issues", list(one, two, three, sub));

        redmine.json("/issues/11.json", map("issue", map("id", 11, "journals", list(
                map("id", 101, "user", bob, "notes", "Same here, see #12 and attachment:shot.png.",
                        "created_on", "2026-01-06T08:00:00Z", "private_notes", false, "details", list()),
                map("id", 102, "user", bob, "notes", "Only for developers", "created_on", "2026-01-06T09:00:00Z",
                        "private_notes", true, "details", list()),
                map("id", 103, "user", alice, "notes", "", "created_on", "2026-01-07T08:00:00Z",
                        "private_notes", false, "details", list(
                                map("property", "attr", "name", "subject", "old_value", "Crash",
                                        "new_value", "Crash on start"),
                                map("property", "attr", "name", "assigned_to_id", "old_value", "4",
                                        "new_value", "5"),
                                map("property", "attr", "name", "priority_id", "old_value", "2",
                                        "new_value", "3"))),
                map("id", 104, "user", alice, "notes", "", "created_on", "2026-01-09T11:00:00Z",
                        "private_notes", false, "details", list(
                                map("property", "attr", "name", "status_id", "old_value", "1", "new_value", "5")))))));
        for (long id : new long[] {12, 13}) {
            redmine.json("/issues/" + id + ".json", map("issue", map("id", id, "journals", list())));
        }
        redmine.json("/issues/99.json", map("issue", map("id", 99, "project", named(3, "Operations"))));
        redmine.file("/attachments/download/21/shot.png", "PNG!".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        redmine.close();
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
        var anonymous = admin.post("/api/v1/admin/import/redmine/connection", "{\"url\": \"%s\"}".formatted(base));
        assertThat(anonymous.status()).as(anonymous.toString()).isEqualTo(200);
        assertThat((String) anonymous.json("$.version")).isEqualTo("Redmine");
        assertThat((Object) anonymous.json("$.login")).isNull();

        var connection = admin.post("/api/v1/admin/import/redmine/connection",
                "{\"url\": \"%s/projects/web/issues?set_filter=1\", \"token\": \"%s\"}".formatted(base, FakeRedmine.KEY));
        assertThat(connection.status()).as(connection.toString()).isEqualTo(200);
        assertThat((String) connection.json("$.url")).isEqualTo(base);
        assertThat((String) connection.json("$.login")).isEqualTo("importer");
        assertThat((Boolean) connection.json("$.admin")).isTrue();

        var search = admin.post("/api/v1/admin/import/redmine/repositories",
                "{\"url\": \"%s\", \"token\": \"%s\", \"query\": \"shop\"}".formatted(base, FakeRedmine.KEY));
        assertThat((List<String>) search.json("$[*].fullName")).containsExactly("web", "web-api");
        assertThat((List<Boolean>) search.json("$[*].isPrivate")).containsExactly(false, true);
        var byIdentifier = admin.post("/api/v1/admin/import/redmine/repositories",
                "{\"url\": \"%s\", \"token\": \"%s\", \"query\": \"WEB\"}".formatted(base, FakeRedmine.KEY));
        assertThat((List<String>) byIdentifier.json("$[*].fullName")).containsExactly("web");
        assertThat((String) byIdentifier.json("$[0].description")).isEqualTo("Web shop");

        var analysis = admin.post("/api/v1/admin/import/redmine/analysis",
                "{\"url\": \"%s\", \"token\": \"%s\", \"repository\": \"web\"}".formatted(base, FakeRedmine.KEY));
        assertThat(analysis.status()).as(analysis.toString()).isEqualTo(200);
        assertThat((Integer) analysis.json("$.issues")).isEqualTo(3);
        assertThat((Integer) analysis.json("$.openIssues")).isEqualTo(1);
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'type:Bug')].suggestion.target"))
                .containsExactly("ISSUE_TYPE");
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'component/Backend')].suggestion.target"))
                .containsExactly("COMPONENT");
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'priority/High')].suggestion.target"))
                .containsExactly("PRIORITY");
        assertThat((List<String>) analysis.json("$.users[?(@.login == 'alice.l')].suggestion.username"))
                .containsExactly("alice"); // by e-mail address
        assertThat((List<String>) analysis.json("$.users[*].login")).contains("bob.builder");
        assertThat(redmine.authorizations).anyMatch(a -> a.endsWith(FakeRedmine.BASIC));
        assertThat(redmine.queries).anyMatch(q -> q.startsWith("/redmine/issues.json ")
                && q.contains("subproject_id=!*") && q.contains("status_id=*") && q.contains("offset=2"));
    }

    @Test
    void importsIssuesWithHistoryRelationsSubtasksAndAttachmentsAndOnlyReads() throws Exception {
        var started = admin.post("/api/v1/admin/import/redmine/jobs", """
                {"url": "%s", "token": "%s", "repository": "web",
                 "newProject": {"key": "SHOP", "name": "Shop"}, "issueTypeId": %d,
                 "labels": {"type:Bug": {"target": "ISSUE_TYPE", "id": %d},
                            "type:Feature": {"target": "ISSUE_TYPE", "id": %d},
                            "type:Support": {"target": "IGNORE"},
                            "priority/High": {"target": "PRIORITY", "id": %d},
                            "priority/Normal": {"target": "IGNORE"},
                            "component/Backend": {"target": "COMPONENT"}},
                 "users": {}}"""
                .formatted(base, FakeRedmine.KEY, typeId("Task"), typeId("Bug"), typeId("Story"),
                        jdbc.queryForObject("select id from priority where name = 'High'", Long.class)));
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        long jobId = ((Number) started.json("$.id")).longValue();
        Map<String, Object> job = waitFor(jobId);
        assertThat(job.get("status")).as(job.toString()).isEqualTo("SUCCEEDED");
        assertThat(job).extractingByKey("counters").asString()
                .contains("issuesTotal=3", "issuesImported=3", "comments=1", "attachments=1");

        var one = admin.get("/api/v1/issues/SHOP-1");
        assertThat((String) one.json("$.origin.kind")).isEqualTo("REDMINE");
        assertThat((String) one.json("$.origin.key")).isEqualTo("web#11");
        assertThat((String) one.json("$.origin.url")).isEqualTo(base + "/issues/11");
        assertThat((String) one.json("$.issueType.name")).isEqualTo("Bug");
        assertThat((String) one.json("$.priority.name")).isEqualTo("High");
        assertThat((String) one.json("$.status.name")).isEqualTo("Closed");
        assertThat((List<String>) one.json("$.components[*].name")).containsExactly("Backend");
        assertThat((String) one.json("$.fixVersions[0].name")).isEqualTo("1.0");
        assertThat((String) one.json("$.dueDate")).isEqualTo("2026-02-01");
        assertThat((String) one.json("$.reporter.username")).isEqualTo("alice");
        assertThat((String) one.json("$.assignee.displayName")).isEqualTo("Dave");

        long attachmentId = jdbc.queryForObject("""
                select a.id from attachment a join issue i on i.id = a.issue_id
                where i.origin_key = 'web#11'""", Long.class);
        String link = "/api/v1/attachments/" + attachmentId + "/content";
        assertThat((String) one.json("$.description")).isEqualTo("""
                ## Crash
                Starting crashes, see SHOP-2 and [the docs](https://docs.example/x).
                - **bold** and `code`
                ![shot.png](%s)
                ```java
                int *x* = 1;
                ```""".formatted(link));
        assertThat(admin.download(link).body()).isEqualTo("PNG!".getBytes(StandardCharsets.UTF_8));

        // Private notes are not imported.
        var comments = admin.get("/api/v1/issues/SHOP-1/comments");
        assertThat((List<Object>) comments.json("$")).hasSize(1);
        assertThat((String) comments.json("$[0].body"))
                .isEqualTo("Same here, see SHOP-2 and [shot.png](" + link + ").");
        assertThat((String) comments.json("$[0].author.displayName")).isEqualTo("Bob Builder");

        // 11 blocks 12; 13 duplicates 11 and is its subtask; 99 (ops) was not imported.
        assertThat(admin.get("/api/v1/issues/SHOP-1/links").body())
                .contains("blocks", "is duplicated by", "SHOP-2", "SHOP-3");
        assertThat(jdbc.queryForObject("select count(*) from issue_link", Long.class)).isEqualTo(2);
        var three = admin.get("/api/v1/issues/SHOP-3");
        assertThat((String) three.json("$.parent.key")).isEqualTo("SHOP-1");
        assertThat((String) three.json("$.issueType.name")).isEqualTo("Sub-task");
        assertThat((String) three.json("$.resolution.name")).isEqualTo("Duplicate");
        assertThat((List<String>) three.json("$.labels")).contains("restricted");
        // The project of 99 was looked up, so an earlier import of ops could have been linked.
        assertThat(redmine.queries).anyMatch(q -> q.startsWith("/redmine/projects/3.json "));

        var history = admin.get("/api/v1/issues/SHOP-1/history");
        assertThat(history.body()).contains("\"fromText\":\"Crash\"", "\"toText\":\"Crash on start\"",
                "\"toText\":\"Redmine web#11\"");
        List<String> assignees = history.json("$[*].items[?(@.field == 'assignee')].fromText");
        assertThat(assignees).containsExactly("Bob Builder");
        List<String> priorities = history.json("$[*].items[?(@.field == 'priority')].toText");
        assertThat(priorities).containsExactly("High");

        assertThat(redmine.methods).isNotEmpty().containsOnly("GET");
        assertThat(redmine.authorizations).allMatch(a -> a.endsWith(FakeRedmine.BASIC));
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class)).isZero();
    }
}
