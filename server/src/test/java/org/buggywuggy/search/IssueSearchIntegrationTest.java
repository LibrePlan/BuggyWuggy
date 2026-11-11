/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class IssueSearchIntegrationTest extends IssueTestSupport {

    private List<String> keys(ApiClient client, String queryJson) {
        var response = client.post("/api/v1/issues/search", queryJson);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response.json("$.items[*].key");
    }

    private long id(String table, String name) {
        return jdbc.queryForObject("select id from " + table + " where name = ?", Long.class, name);
    }

    @Test
    void findsTextInSummaryDescriptionAndCommentsAlsoAsPrefix() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String summary = createIssue(admin, "BUG", "Task", "Database migration fails");
        String description = createIssue(admin, "BUG", "Task", "Slow startup");
        admin.patch("/api/v1/issues/" + description, """
                {"description": "Probably caused by the migration scripts."}""");
        String comment = createIssue(admin, "BUG", "Bug", "Login button");
        admin.post("/api/v1/issues/" + comment + "/comments", """
                {"body": "Same root cause as the migrations problem."}""");
        createIssue(admin, "BUG", "Task", "Unrelated");

        assertThat(keys(admin, """
                {"text": "migr"}""")).containsExactlyInAnyOrder(summary, description, comment);
        // All words must match; the summary match ranks first.
        assertThat(keys(admin, """
                {"text": "database migr"}""")).containsExactly(summary);
        assertThat(keys(admin, """
                {"text": "migration"}""").getFirst()).isEqualTo(summary);
        // An issue key finds that issue, also in lower case.
        assertThat(keys(admin, """
                {"text": "%s"}""".formatted(comment.toLowerCase()))).first().isEqualTo(comment);
        assertThat(keys(admin, """
                {"text": "nothing-like-this"}""")).isEmpty();
    }

    @Test
    void combinesStructuredConditions() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "OPS", "Operations");
        long ottoId = createUser(admin, "otto", "otto-password-12");
        admin.put("/api/v1/projects/BUG/roles/" + roleId("Developers") + "/users/" + ottoId, null);
        String bug1 = createIssue(admin, "BUG", "Bug", "Crash");
        String bug2 = createIssue(admin, "BUG", "Task", "Docs");
        String ops1 = createIssue(admin, "OPS", "Task", "Deploy");
        admin.patch("/api/v1/issues/" + bug1, """
                {"assigneeId": %d, "labels": ["urgent", "backend"], "priorityId": %d}"""
                .formatted(ottoId, id("priority", "Highest")));
        admin.patch("/api/v1/issues/" + bug2, """
                {"labels": ["docs"]}""");
        long resolve = ((Number) ((List<Object>) admin.get("/api/v1/issues/" + ops1 + "/transitions")
                .json("$[?(@.name == 'Resolve Issue')].id")).getFirst()).longValue();
        admin.post("/api/v1/issues/" + ops1 + "/transitions", """
                {"transitionId": %d}""".formatted(resolve));
        long bugProject = jdbc.queryForObject("select id from project where key = 'BUG'", Long.class);

        assertThat(keys(admin, """
                {"projectIds": [%d]}""".formatted(bugProject))).containsExactlyInAnyOrder(bug1, bug2);
        assertThat(keys(admin, """
                {"issueTypeIds": [%d]}""".formatted(typeId("Bug")))).containsExactly(bug1);
        assertThat(keys(admin, """
                {"assignees": ["%d"]}""".formatted(ottoId))).containsExactly(bug1);
        assertThat(keys(admin, """
                {"assignees": ["none"], "projectIds": [%d]}""".formatted(bugProject))).containsExactly(bug2);
        assertThat(keys(admin, """
                {"labels": ["urgent", "docs"]}""")).containsExactlyInAnyOrder(bug1, bug2);
        assertThat(keys(admin, """
                {"resolution": "RESOLVED"}""")).containsExactly(ops1);
        assertThat(keys(admin, """
                {"resolution": "UNRESOLVED", "labels": ["docs"]}""")).containsExactly(bug2);
        assertThat(keys(admin, """
                {"reporters": ["me"], "statusIds": [%d]}""".formatted(id("status", "Resolved"))))
                .containsExactly(ops1);
        assertThat(keys(admin, """
                {"sort": "KEY", "direction": "ASC"}""")).containsExactly(bug1, bug2, ops1);
        assertThat(keys(admin, """
                {"sort": "PRIORITY", "direction": "DESC", "projectIds": [%d]}""".formatted(bugProject)).getFirst())
                .isEqualTo(bug1);
        assertThat(keys(admin, """
                {"createdFrom": "2000-01-01", "createdTo": "2000-12-31"}""")).isEmpty();

        // "me" is whoever runs the search.
        ApiClient otto = loginAs("otto", "otto-password-12");
        assertThat(keys(otto, """
                {"assignees": ["me"]}""")).containsExactly(bug1);

        var page = admin.post("/api/v1/issues/search?size=2&page=1", "{\"sort\": \"KEY\", \"direction\": \"ASC\"}");
        assertThat((Integer) page.json("$.total")).isEqualTo(3);
        assertThat((List<String>) page.json("$.items[*].key")).containsExactly(ops1);
        assertThat((String) page.json("$.items[0].project.key")).isEqualTo("OPS");
        assertThat((String) page.json("$.items[0].resolution.name")).isEqualTo("Done");
    }

    @Test
    void onlyFindsIssuesInBrowsableProjects() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "SECRET", "Secret");
        admin.delete("/api/v1/projects/SECRET/roles/" + roleId("Users") + "/groups/" + groupId("users"));
        String visible = createIssue(admin, "BUG", "Task", "Shared plan");
        String hidden = createIssue(admin, "SECRET", "Task", "Secret plan");
        createUser(admin, "otto", "otto-password-12");
        ApiClient otto = loginAs("otto", "otto-password-12");

        assertThat(keys(admin, "{\"text\": \"plan\"}")).containsExactlyInAnyOrder(visible, hidden);
        assertThat(keys(otto, "{\"text\": \"plan\"}")).containsExactly(visible);
        assertThat(keys(otto, "{\"text\": \"" + hidden + "\"}")).isEmpty();
    }

    @Test
    void rejectsInvalidQueries() {
        ApiClient admin = setupAndLoginAdmin();
        var response = admin.post("/api/v1/issues/search", """
                {"assignees": ["someone"]}""");
        assertThat(response.status()).isEqualTo(400);
        assertThat(admin.post("/api/v1/issues/search", """
                {"sort": "NONSENSE"}""").status()).isEqualTo(400);
    }

    @Test
    void suggestsLabelsAndUsers() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Task", "Labelled");
        admin.patch("/api/v1/issues/" + key, """
                {"labels": ["backend", "frontend", "urgent"]}""");
        long ottoId = createUser(admin, "otto", "otto-password-12");

        assertThat((List<String>) admin.get("/api/v1/users/picker?ids=" + ottoId).json("$[*].username"))
                .containsExactly("otto");
        assertThat((List<String>) admin.get("/api/v1/labels?query=END").json("$"))
                .containsExactly("backend", "frontend");
        assertThat((List<String>) admin.get("/api/v1/users/picker?query=ott").json("$[*].username"))
                .containsExactly("otto");
    }
}
