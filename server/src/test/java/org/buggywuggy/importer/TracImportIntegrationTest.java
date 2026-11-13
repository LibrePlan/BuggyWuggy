/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.buggywuggy.importer.FakeTrac.list;
import static org.buggywuggy.importer.FakeTrac.map;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TracImportIntegrationTest extends IssueTestSupport {

    private static final Instant CREATED = Instant.parse("2026-01-05T09:00:00Z");

    private FakeTrac trac;
    private String base;
    private String token;
    private ApiClient admin;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("delete from import_source");
        jdbc.update("delete from outbox_event");
        trac = new FakeTrac();
        base = trac.url();
        token = FakeTrac.USER + ":" + FakeTrac.PASSWORD;
        admin = setupAndLoginAdmin();
        createUser(admin, "alice", "alice-password-123");

        trac.method("system.getAPIVersion", p -> list(1, 1, 8));
        trac.method("ticket.component.getAll", p -> list("core", "ui"));
        trac.method("ticket.milestone.getAll", p -> list("1.0"));
        trac.method("ticket.milestone.get", p -> map("name", "1.0", "description", "First",
                "due", Instant.parse("2026-03-01T00:00:00Z"), "completed", Instant.parse("2026-03-02T10:00:00Z")));
        trac.method("ticket.query", p -> list(1, 2, 3));
        Map<Integer, Map<String, Object>> tickets = Map.of(
                1, map("summary", "Crash on start", "reporter", "Alice Liddell <alice@example.org>", "owner", "dave",
                        "status", "closed", "resolution", "fixed", "type", "defect", "priority", "major",
                        "component", "core", "severity", "", "milestone", "1.0", "keywords", "crash, regression",
                        "description", """
                                == Crash ==
                                Starting crashes, see #2 and [https://docs.example/x the docs].
                                 * '''bold''' and {{{code}}}
                                [[Image(shot.png)]]
                                {{{
                                #!java
                                int '''x''' = 1;
                                }}}"""),
                2, map("summary", "Show the basket", "reporter", "bob", "owner", "", "status", "new",
                        "resolution", "", "type", "enhancement", "priority", "minor", "component", "ui",
                        "milestone", "", "keywords", "", "description", "", "blockedby", "1"),
                3, map("summary", "Crashes too", "reporter", "bob", "owner", "", "status", "closed",
                        "resolution", "duplicate", "type", "task", "priority", "minor", "component", "core",
                        "milestone", "", "keywords", "", "description", "", "parents", "1", "sensitive", "1"));
        trac.method("ticket.get", p -> {
            int id = (Integer) p.get(0);
            return list(id, CREATED, Instant.parse("2026-01-09T11:00:00Z"), tickets.get(id));
        });
        trac.method("ticket.listAttachments", p -> (Integer) p.get(0) == 1
                ? list(list("shot.png", "A shot", 4, CREATED, "alice")) : list());
        Instant t1 = Instant.parse("2026-01-06T08:00:00Z");
        Instant t2 = Instant.parse("2026-01-07T08:00:00Z");
        Instant t3 = Instant.parse("2026-01-09T11:00:00Z");
        trac.method("ticket.changeLog", p -> (Integer) p.get(0) != 1 ? list() : list(
                list(t1, "bob", "comment", "1", "Same here, see ticket:2 and attachment:shot.png.", 1),
                list(t2, "Alice Liddell <alice@example.org>", "summary", "Crash", "Crash on start", 1),
                list(t2, "Alice Liddell <alice@example.org>", "owner", "bob", "dave", 1),
                list(t2, "Alice Liddell <alice@example.org>", "priority", "minor", "major", 1),
                list(t2, "Alice Liddell <alice@example.org>", "comment", "2", "", 1),
                list(t3, "dave", "resolution", "", "fixed", 1),
                list(t3, "dave", "status", "new", "closed", 1),
                list(t3, "dave", "comment", "3", "Fixed in [123].", 1)));
        trac.file(FakeTrac.PATH + "/raw-attachment/ticket/1/shot.png", "PNG!".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        trac.close();
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
    void onlyReadOnlyMethodsCanBeCalled() {
        ReadOnlyHttp http = new ReadOnlyHttp("trac", Set.of("127.0.0.1"), null, Map.of(), TracLimits.NONE,
                Clock.systemUTC());
        URI endpoint = URI.create(base + "/rpc");
        assertThat(http.rpc(endpoint, "system.getAPIVersion")).isEqualTo(List.of(1, 1, 8));
        for (String method : List.of("ticket.update", "ticket.create", "ticket.delete", "system.multicall",
                "wiki.putPage", "ticket.milestone.update")) {
            assertThatThrownBy(() -> http.rpc(endpoint, method, 1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(trac.calls).containsExactly("system.getAPIVersion[]");
    }

    @Test
    void connectsAndSuggestsAMapping() {
        var anonymous = admin.post("/api/v1/admin/import/trac/connection", "{\"url\": \"%s\"}".formatted(base));
        assertThat(anonymous.status()).as(anonymous.toString()).isEqualTo(200);
        assertThat((String) anonymous.json("$.version")).isEqualTo("Trac (XML-RPC 1.8)");
        assertThat((Object) anonymous.json("$.login")).isNull();

        var connection = admin.post("/api/v1/admin/import/trac/connection",
                "{\"url\": \"%s/ticket/1\", \"token\": \"%s\"}".formatted(base, token));
        assertThat(connection.status()).as(connection.toString()).isEqualTo(200);
        assertThat((String) connection.json("$.url")).isEqualTo(base);
        assertThat((String) connection.json("$.login")).isEqualTo(FakeTrac.USER);

        var missing = admin.post("/api/v1/admin/import/trac/connection",
                "{\"url\": \"%s/other\"}".formatted(base.substring(0, base.length() - FakeTrac.PATH.length())));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body()).contains("XML-RPC");

        var search = admin.post("/api/v1/admin/import/trac/repositories",
                "{\"url\": \"%s\", \"token\": \"%s\", \"query\": \"\"}".formatted(base, token));
        assertThat((List<String>) search.json("$[*].fullName")).containsExactly("proj");

        var analysis = admin.post("/api/v1/admin/import/trac/analysis",
                "{\"url\": \"%s\", \"token\": \"%s\", \"repository\": \"proj\"}".formatted(base, token));
        assertThat(analysis.status()).as(analysis.toString()).isEqualTo(200);
        assertThat((Integer) analysis.json("$.issues")).isEqualTo(3);
        assertThat((Integer) analysis.json("$.openIssues")).isEqualTo(1);
        assertThat((List<Integer>) analysis.json("$.labels[?(@.name == 'type:defect')].suggestion.id"))
                .containsExactly((int) typeId("Bug"));
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'component/core')].suggestion.target"))
                .containsExactly("COMPONENT");
        assertThat((List<String>) analysis.json("$.labels[?(@.name == 'crash')].suggestion.target"))
                .containsExactly("LABEL");
        assertThat((List<String>) analysis.json("$.users[?(@.login == 'alice')].suggestion.username"))
                .containsExactly("alice");
        assertThat(trac.requests).allMatch(r -> r.startsWith("POST "));
    }

    @Test
    void importsTicketsWithHistoryDependenciesSubticketsAndAttachmentsAndOnlyReads() throws Exception {
        var started = admin.post("/api/v1/admin/import/trac/jobs", """
                {"url": "%s", "token": "%s", "repository": "proj",
                 "newProject": {"key": "SHOP", "name": "Shop"}, "issueTypeId": %d,
                 "labels": {"type:defect": {"target": "ISSUE_TYPE", "id": %d},
                            "type:enhancement": {"target": "ISSUE_TYPE", "id": %d},
                            "type:task": {"target": "IGNORE"},
                            "priority/major": {"target": "PRIORITY", "id": %d},
                            "priority/minor": {"target": "IGNORE"},
                            "component/core": {"target": "COMPONENT"},
                            "component/ui": {"target": "COMPONENT"}},
                 "users": {}}"""
                .formatted(base, token, typeId("Task"), typeId("Bug"), typeId("Story"),
                        jdbc.queryForObject("select id from priority where name = 'High'", Long.class)));
        assertThat(started.status()).as(started.toString()).isEqualTo(201);
        long jobId = ((Number) started.json("$.id")).longValue();
        Map<String, Object> job = waitFor(jobId);
        assertThat(job.get("status")).as(job.toString()).isEqualTo("SUCCEEDED");
        assertThat(job).extractingByKey("counters").asString()
                .contains("issuesTotal=3", "issuesImported=3", "comments=2", "attachments=1");

        var one = admin.get("/api/v1/issues/SHOP-1");
        assertThat((String) one.json("$.origin.kind")).isEqualTo("TRAC");
        assertThat((String) one.json("$.origin.key")).isEqualTo("proj#1");
        assertThat((String) one.json("$.origin.url")).isEqualTo(base + "/ticket/1");
        assertThat((String) one.json("$.issueType.name")).isEqualTo("Bug");
        assertThat((String) one.json("$.priority.name")).isEqualTo("High");
        assertThat((String) one.json("$.status.name")).isEqualTo("Closed");
        assertThat((List<String>) one.json("$.labels")).containsExactlyInAnyOrder("crash", "regression");
        assertThat((List<String>) one.json("$.components[*].name")).containsExactly("core");
        assertThat((String) one.json("$.fixVersions[0].name")).isEqualTo("1.0");
        assertThat((String) one.json("$.reporter.username")).isEqualTo("alice");
        assertThat((String) one.json("$.assignee.username")).startsWith("dave");

        long attachmentId = jdbc.queryForObject("""
                select a.id from attachment a join issue i on i.id = a.issue_id
                where i.origin_key = 'proj#1'""", Long.class);
        String link = "/api/v1/attachments/" + attachmentId + "/content";
        assertThat((String) one.json("$.description")).isEqualTo("""
                ## Crash
                Starting crashes, see SHOP-2 and [the docs](https://docs.example/x).
                - **bold** and `code`
                ![shot.png](%s)
                ```java
                int '''x''' = 1;
                ```""".formatted(link));
        assertThat(admin.download(link).body()).isEqualTo("PNG!".getBytes(StandardCharsets.UTF_8));

        var comments = admin.get("/api/v1/issues/SHOP-1/comments");
        assertThat((List<String>) comments.json("$[*].body")).containsExactly(
                "Same here, see SHOP-2 and [shot.png](" + link + ").", "Fixed in [123].");

        // 2 is blocked by 1 (MasterTickets); 3 is a sub-ticket of 1 (Subtickets) and sensitive.
        assertThat(admin.get("/api/v1/issues/SHOP-2/links").body()).contains("is blocked by", "SHOP-1");
        var three = admin.get("/api/v1/issues/SHOP-3");
        assertThat((String) three.json("$.parent.key")).isEqualTo("SHOP-1");
        assertThat((String) three.json("$.resolution.name")).isEqualTo("Duplicate");
        assertThat((List<String>) three.json("$.labels")).contains("restricted");

        var history = admin.get("/api/v1/issues/SHOP-1/history");
        assertThat(history.body()).contains("\"fromText\":\"Crash\"", "\"toText\":\"Crash on start\"",
                "\"toText\":\"Trac proj#1\"");
        List<String> priorities = history.json("$[*].items[?(@.field == 'priority')].toText");
        assertThat(priorities).containsExactly("High");

        assertThat(trac.requests).allMatch(r -> r.startsWith("POST " + FakeTrac.PATH + "/login/rpc ")
                || r.startsWith("GET " + FakeTrac.PATH + "/raw-attachment/"));
        assertThat(trac.requests).allMatch(r -> r.endsWith(FakeTrac.BASIC));
        assertThat(trac.calls).allMatch(c -> ReadOnlyHttp.READ_ONLY_RPC_METHODS.contains(c.substring(0,
                c.indexOf('['))));
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Long.class)).isZero();
    }

    /** No rate limits, for the direct test of {@link ReadOnlyHttp#rpc}. */
    private static final class TracLimits {
        static final ReadOnlyHttp.RateLimits NONE = new ReadOnlyHttp.RateLimits() {
            @Override
            public java.util.Optional<Instant> retryAt(java.net.http.HttpResponse<?> response, Instant now) {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<Instant> pauseUntil(java.net.http.HttpResponse<?> response, Instant now) {
                return java.util.Optional.empty();
            }
        };
    }
}
