/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.buggywuggy.importer.FakeJira.fields;
import static org.buggywuggy.importer.FakeJira.issue;
import static org.buggywuggy.importer.FakeJira.list;
import static org.buggywuggy.importer.FakeJira.map;
import static org.buggywuggy.importer.FakeJira.user;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JiraImportIntegrationTest extends IssueTestSupport {

    private static final String EPIC_LINK = "customfield_10008";

    private FakeJira jira;
    private String base;
    private ApiClient admin;
    private Map<String, Object> alice;
    private Map<String, Object> bob;
    private Map<String, Object> carol;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from import_source");
        jdbc.update("delete from outbox_event");
        jira = new FakeJira();
        base = jira.url();
        admin = setupAndLoginAdmin();
        createUser(admin, "alice", "alice-password-123");

        alice = user("JIRAUSER10100", "alice.l", "Alice Liddell", "alice@example.org");
        bob = user("bob", "bob", "Bob Builder", "bob@jira.example");
        carol = user("JIRAUSER10300", "carol", "Carol", null);
        jira.json("/serverInfo", map("baseUrl", base, "version", "9.12.4", "deploymentType", "DataCenter",
                "serverTitle", "Jira"));
        jira.json("/myself", user("JIRAUSER1", "importer", "Importer", "importer@example.org"));
        jira.json("/mypermissions", map("permissions", map("ADMINISTER", map("havePermission", true))));
        Map<String, Object> project = map("id", "10000", "key", "WEB", "name", "Web shop", "description", "");
        jira.json("/project/WEB", project);
        jira.json("/project", list(project, map("id", "10001", "key", "OPS", "name", "Operations")));
        jira.json("/project/WEB/components", list(map("name", "Backend", "description", "The server")));
        jira.json("/project/WEB/versions", list(map("id", "1", "name", "1.0", "description", "First",
                "released", true, "releaseDate", "2026-03-01")));
        jira.json("/field", list(map("id", "summary", "name", "Summary"), map("id", EPIC_LINK, "name", "Epic Link",
                "schema", map("type", "any", "custom", "com.pyxis.greenhopper.jira:gh-epic-link"))));
        jira.json("/status", list(map("id", "1", "name", "Open", "statusCategory", map("key", "new")),
                map("id", "6", "name", "Closed", "statusCategory", map("key", "done"))));

        Map<String, Object> one = fields("Crash on start", "Bug", alice, "6", "done");
        one.put("description", """
                h2. Crash
                Starting crashes, see WEB-3, [the docs|https://docs.example/x] and UTF-8.
                * *bold* and {{code}}
                ** nested
                !shot.png|thumbnail!
                {code:java}
                int *x* = 1;
                {code}""");
        one.put("priority", map("id", "2", "name", "Major"));
        one.put("resolution", map("id", "1", "name", "Fixed"));
        one.put("resolutiondate", "2026-01-09T11:00:00.000+0000");
        one.put("labels", list("regression"));
        one.put("components", list(map("id", "1", "name", "Backend")));
        one.put("fixVersions", list(map("id", "1", "name", "1.0")));
        one.put("assignee", bob);
        one.put("duedate", "2026-02-01");
        one.put("comment", map("startAt", 0, "maxResults", 0, "total", 2, "comments", list()));
        one.put("attachment", list(map("id", "20001", "filename", "shot.png", "author", alice,
                "created", "2026-01-05T09:00:00.000+0000", "size", 4, "mimeType", "image/png",
                "content", base + "/secure/attachment/20001/shot.png")));
        one.put("issuelinks", list(
                map("id", "1", "type", map("name", "Blocks", "inward", "is blocked by", "outward", "blocks"),
                        "outwardIssue", map("id", "10002", "key", "WEB-2")),
                map("id", "2", "type", map("name", "Duplicate", "inward", "is duplicated by",
                        "outward", "duplicates"), "inwardIssue", map("id", "10002", "key", "WEB-2")),
                map("id", "3", "type", map("name", "Relates", "inward", "relates to", "outward", "relates to"),
                        "outwardIssue", map("id", "30001", "key", "OPS-7"))));
        Map<String, Object> two = fields("Show the basket", "Story", bob, "1", "new");
        two.put(EPIC_LINK, "WEB-3");
        two.put("issuelinks", list(map("id", "2", "type", map("name", "Duplicate", "inward", "is duplicated by",
                "outward", "duplicates"), "outwardIssue", map("id", "10001", "key", "WEB-1"))));
        Map<String, Object> three = fields("Checkout", "Epic", carol, "1", "new");
        Map<String, Object> four = fields("Write a test", "Sub-task", bob, "1", "new");
        four.put("parent", map("id", "10001", "key", "WEB-1"));
        four.put("security", map("id", "1", "name", "Staff"));
        jira.pages("/search", "issues", list(issue("10001", "WEB-1", one)),
                list(issue("10002", "WEB-2", two), issue("10003", "WEB-3", three), issue("10004", "WEB-4", four)));

        Map<String, Object> restricted = map("id", "502", "author", bob, "body", "Only for developers",
                "created", "2026-01-06T10:00:00.000+0000", "updated", "2026-01-06T10:00:00.000+0000",
                "visibility", map("type", "role", "value", "Developers"));
        jira.pages("/issue/10001/comment", "comments", list(map("id", "501", "author", carol,
                "body", "Same here, see WEB-2 and [^shot.png], [~alice.l].",
                "created", "2026-01-06T08:00:00.000+0000", "updated", "2026-01-06T08:00:00.000+0000")),
                list(restricted));
        jira.json("/issue/10001", map("id", "10001", "key", "WEB-1", "changelog", map("startAt", 0,
                "maxResults", 2, "total", 2, "histories", list(
                        map("id", "1", "author", bob, "created", "2026-01-07T08:00:00.000+0000", "items", list(
                                map("field", "summary", "fieldtype", "jira", "from", null, "fromString", "Crash",
                                        "to", null, "toString", "Crash on start"),
                                map("field", "assignee", "fieldtype", "jira", "from", "JIRAUSER10400",
                                        "fromString", "Dave", "to", "bob", "toString", "Bob Builder"),
                                map("field", "priority", "fieldtype", "jira", "from", "3", "fromString", "Medium",
                                        "to", "2", "toString", "Major"))),
                        map("id", "2", "author", alice, "created", "2026-01-09T11:00:00.000+0000", "items", list(
                                map("field", "resolution", "fieldtype", "jira", "from", null, "fromString", null,
                                        "to", "1", "toString", "Fixed"),
                                map("field", "status", "fieldtype", "jira", "from", "1", "fromString", "Open",
                                        "to", "6", "toString", "Closed")))))));
        jira.json("/user", user("JIRAUSER10400", "dave", "Dave", null));
        jira.json("/issue/10001/remotelink", list(
                map("id", 1, "object", map("url", "https://github.com/acme/web/pull/5", "title", "Fix crash")),
                map("id", 2, "object", map("url", "https://wiki.example/design", "title", "Design"))));
        for (String id : new String[] {"10002", "10003", "10004"}) {
            jira.pages("/issue/" + id + "/comment", "comments", list());
            jira.json("/issue/" + id, map("id", id, "changelog", map("startAt", 0, "maxResults", 0, "total", 0,
                    "histories", list())));
        }
        jira.file("/secure/attachment/20001/shot.png", "PNG!".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        jira.close();
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
    void connectsToServerAndCloudAndSuggestsAMapping() {
        var anonymous = admin.post("/api/v1/admin/import/jira/connection", "{\"url\": \"%s\"}".formatted(base));
        assertThat(anonymous.status()).as(anonymous.toString()).isEqualTo(200);
        assertThat((String) anonymous.json("$.version")).isEqualTo("Jira Data Center 9.12.4");
        assertThat((Object) anonymous.json("$.login")).isNull();

        var connection = admin.post("/api/v1/admin/import/jira/connection",
                "{\"url\": \"%s/browse/WEB-1\", \"token\": \"%s\"}".formatted(base, FakeJira.TOKEN));
        assertThat(connection.status()).as(connection.toString()).isEqualTo(200);
        assertThat((String) connection.json("$.url")).isEqualTo(base);
        assertThat((String) connection.json("$.login")).isEqualTo("importer");
        assertThat((Boolean) connection.json("$.admin")).isTrue();

        var search = admin.post("/api/v1/admin/import/jira/repositories",
                "{\"url\": \"%s\", \"token\": \"%s\", \"query\": \"shop\"}".formatted(base, FakeJira.TOKEN));
        assertThat((List<String>) search.json("$[*].fullName")).containsExactly("WEB");
        assertThat((String) search.json("$[0].description")).isEqualTo("Web shop");
        var byKey = admin.post("/api/v1/admin/import/jira/repositories",
                "{\"url\": \"%s\", \"token\": \"%s\", \"query\": \"web\"}".formatted(base, FakeJira.TOKEN));
        assertThat((List<String>) byKey.json("$[*].fullName")).containsExactly("WEB");

        // Cloud: e-mail address and API token, and the search with page tokens.
        jira.json("/serverInfo", map("baseUrl", base, "version", "1001.0.0", "deploymentType", "Cloud"));
        jira.json("/myself", map("accountId", "5b10a", "displayName", "Importer",
                "emailAddress", FakeJira.EMAIL));
        jira.cloudPages("/search/jql", "issues", list(issue("10001", "WEB-1", fields("Crash", "Bug", alice, "6",
                "done"))), list(issue("10003", "WEB-3", fields("Checkout", "Epic", carol, "1", "new"))));
        var cloud = admin.post("/api/v1/admin/import/jira/connection", "{\"url\": \"%s\", \"token\": \"%s:%s\"}"
                .formatted(base, FakeJira.EMAIL, FakeJira.TOKEN));
        assertThat(cloud.status()).as(cloud.toString()).isEqualTo(200);
        assertThat((String) cloud.json("$.version")).isEqualTo("Jira Cloud");
        assertThat((String) cloud.json("$.login")).isEqualTo(FakeJira.EMAIL);

        var analysis = admin.post("/api/v1/admin/import/jira/analysis",
                "{\"url\": \"%s\", \"token\": \"%s:%s\", \"repository\": \"web\"}"
                        .formatted(base, FakeJira.EMAIL, FakeJira.TOKEN));
        assertThat(analysis.status()).as(analysis.toString()).isEqualTo(200);
        assertThat((Integer) analysis.json("$.issues")).isEqualTo(2);
        assertThat((Integer) analysis.json("$.openIssues")).isEqualTo(1);
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'type:Bug')].suggestion.target"))
                .containsExactly("ISSUE_TYPE");
        assertThat((List<Integer>) analysis.json("$.labels[?(@.name == 'type:Epic')].suggestion.id"))
                .containsExactly((int) typeId("Epic"));
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'component/Backend')].suggestion.target"))
                .containsExactly("COMPONENT");
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'priority/Medium')].suggestion.target"))
                .containsExactly("PRIORITY");
        assertThat((List<String>) analysis.json("$.users[?(@.login == 'alice.l')].suggestion.username"))
                .containsExactly("alice"); // by e-mail address
        assertThat(jira.authorizations).anyMatch(a -> a.endsWith(FakeJira.BASIC));
        assertThat(jira.queries).anyMatch(q -> q.startsWith("/rest/api/2/search/jql ")
                && q.contains("nextPageToken=1"));
    }

    @Test
    void importsIssuesWithHistoryLinksHierarchyAndAttachmentsAndOnlyReads() throws Exception {
        jira.rateLimitOnce("/project/WEB/versions");
        var started = admin.post("/api/v1/admin/import/jira/jobs", """
                {"url": "%s", "token": "%s", "repository": "WEB",
                 "newProject": {"key": "SHOP", "name": "Shop"}, "issueTypeId": %d,
                 "labels": {"type:Bug": {"target": "ISSUE_TYPE", "id": %d},
                            "type:Epic": {"target": "ISSUE_TYPE", "id": %d},
                            "type:Story": {"target": "ISSUE_TYPE", "id": %d},
                            "type:Sub-task": {"target": "IGNORE"}, "type:Task": {"target": "IGNORE"},
                            "priority/Major": {"target": "PRIORITY", "id": %d},
                            "priority/Medium": {"target": "IGNORE"},
                            "component/Backend": {"target": "COMPONENT"}},
                 "users": {}}"""
                .formatted(base, FakeJira.TOKEN, typeId("Task"), typeId("Bug"), typeId("Epic"), typeId("Story"),
                        jdbc.queryForObject("select id from priority where name = 'High'", Long.class)));
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        long jobId = ((Number) started.json("$.id")).longValue();
        Map<String, Object> job = waitFor(jobId);
        assertThat(job.get("status")).as(job.toString()).isEqualTo("SUCCEEDED");
        assertThat(job).extractingByKey("counters").asString()
                .contains("issuesTotal=4", "issuesImported=4", "comments=1", "attachments=1");

        var one = admin.get("/api/v1/issues/SHOP-1");
        assertThat((String) one.json("$.origin.kind")).isEqualTo("JIRA");
        assertThat((String) one.json("$.origin.key")).isEqualTo("WEB-1");
        assertThat((String) one.json("$.origin.url")).isEqualTo(base + "/browse/WEB-1");
        assertThat((String) one.json("$.issueType.name")).isEqualTo("Bug");
        assertThat((String) one.json("$.priority.name")).isEqualTo("High");
        assertThat((String) one.json("$.status.name")).isEqualTo("Closed");
        assertThat((List<String>) one.json("$.labels")).containsExactly("regression");
        assertThat((List<String>) one.json("$.components[*].name")).containsExactly("Backend");
        assertThat((String) one.json("$.fixVersions[0].name")).isEqualTo("1.0");
        assertThat((String) one.json("$.dueDate")).isEqualTo("2026-02-01");
        assertThat((String) one.json("$.reporter.username")).isEqualTo("alice");
        assertThat((String) one.json("$.assignee.displayName")).isEqualTo("Bob Builder");
        assertThat((List<String>) one.json("$.remoteLinks[*].relation")).containsExactlyInAnyOrder("PULL_REQUEST",
                "OTHER");

        long attachmentId = jdbc.queryForObject("""
                select a.id from attachment a join issue i on i.id = a.issue_id
                where i.origin_key = 'WEB-1'""", Long.class);
        String link = "/api/v1/attachments/" + attachmentId + "/content";
        assertThat((String) one.json("$.description")).isEqualTo("""
                ## Crash
                Starting crashes, see SHOP-3, [the docs](https://docs.example/x) and UTF-8.
                - **bold** and `code`
                  - nested
                ![shot.png](%s)
                ```java
                int *x* = 1;
                ```""".formatted(link));
        assertThat(admin.download(link).body()).isEqualTo("PNG!".getBytes(StandardCharsets.UTF_8));

        // Restricted comments are not imported.
        var comments = admin.get("/api/v1/issues/SHOP-1/comments");
        assertThat((List<Object>) comments.json("$")).hasSize(1);
        assertThat((String) comments.json("$[0].body"))
                .isEqualTo("Same here, see SHOP-2 and [shot.png](" + link + "), @Alice Liddell.");

        var links = admin.get("/api/v1/issues/SHOP-1/links").body();
        assertThat(links).contains("blocks", "is duplicated by", "SHOP-2").doesNotContain("OPS-7");
        assertThat(jdbc.queryForObject("select count(*) from issue_link", Long.class)).isEqualTo(2);

        // The story is in the epic; the sub-task under the bug.
        assertThat((String) admin.get("/api/v1/issues/SHOP-2").json("$.parent.key")).isEqualTo("SHOP-3");
        var four = admin.get("/api/v1/issues/SHOP-4");
        assertThat((String) four.json("$.parent.key")).isEqualTo("SHOP-1");
        assertThat((String) four.json("$.issueType.name")).isEqualTo("Sub-task");
        assertThat((List<String>) four.json("$.labels")).contains("restricted");

        var history = admin.get("/api/v1/issues/SHOP-1/history");
        assertThat(history.body()).contains("\"fromText\":\"Crash\"", "\"toText\":\"Crash on start\"",
                "\"toText\":\"Jira WEB-1\"");
        List<String> assignees = history.json("$[*].items[?(@.field == 'assignee')].fromText");
        assertThat(assignees).containsExactly("Dave");
        List<String> priorities = history.json("$[*].items[?(@.field == 'priority')].toText");
        assertThat(priorities).containsExactly("High");

        assertThat(jdbc.queryForObject("select count(*) from import_job_log where job_id = ? and message like ?",
                Long.class, jobId, "Waiting until %rate limit%")).isEqualTo(1);
        assertThat(jira.methods).isNotEmpty().containsOnly("GET");
        assertThat(jira.authorizations).allMatch(a -> a.endsWith("Bearer " + FakeJira.TOKEN));
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class)).isZero();
    }
}
