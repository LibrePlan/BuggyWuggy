/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class SavedFilterIntegrationTest extends IntegrationTest {

    @Test
    void privateFiltersAreOnlyVisibleToTheirOwner() {
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "otto", "otto-password-12");
        var created = admin.post("/api/v1/filters", """
                {"name": "My open bugs", "query": {"assignees": ["me"], "resolution": "UNRESOLVED"}}""");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat((String) created.json("$.sharedWith")).isEqualTo("PRIVATE");
        assertThat((List<String>) created.json("$.query.assignees")).containsExactly("me");
        assertThat((Boolean) created.json("$.mine")).isTrue();
        long id = ((Number) created.json("$.id")).longValue();

        ApiClient otto = loginAs("otto", "otto-password-12");
        assertThat((List<Object>) otto.get("/api/v1/filters").json("$")).isEmpty();
        assertThat(otto.get("/api/v1/filters/" + id).status()).isEqualTo(404);

        var duplicate = admin.post("/api/v1/filters", """
                {"name": "my OPEN bugs", "query": {}}""");
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat((String) duplicate.json("$.code")).isEqualTo("filter.name-taken");
    }

    @Test
    void sharedFiltersCanBeUsedButNotChangedByOthers() {
        ApiClient admin = setupAndLoginAdmin();
        long projectId = createProject(admin, "BUG", "Bug Tracker");
        createUser(admin, "otto", "otto-password-12");
        long everyone = ((Number) admin.post("/api/v1/filters", """
                {"name": "Everything", "query": {"text": "crash"}, "sharedWith": "LOGGED_IN"}""")
                .json("$.id")).longValue();
        var projectFilter = admin.post("/api/v1/filters", """
                {"name": "Bug tracker", "query": {"projectIds": [%d]}, "sharedWith": "PROJECT", "projectId": %d}"""
                .formatted(projectId, projectId));
        assertThat(projectFilter.status()).as(projectFilter.toString()).isEqualTo(201);
        assertThat((String) projectFilter.json("$.project.key")).isEqualTo("BUG");

        ApiClient otto = loginAs("otto", "otto-password-12");
        var list = otto.get("/api/v1/filters");
        assertThat((List<String>) list.json("$[*].name")).containsExactly("Bug tracker", "Everything");
        assertThat((List<Boolean>) list.json("$[*].mine")).containsOnly(false);
        assertThat(otto.patch("/api/v1/filters/" + everyone, "{\"name\": \"Mine now\"}").status()).isEqualTo(403);
        assertThat(otto.delete("/api/v1/filters/" + everyone).status()).isEqualTo(403);

        // Without access to the project, its filters disappear.
        admin.delete("/api/v1/projects/BUG/roles/" + roleId("Users") + "/groups/" + groupId("users"));
        assertThat((List<String>) otto.get("/api/v1/filters").json("$[*].name")).containsExactly("Everything");
    }

    @Test
    void ownerUpdatesAndDeletesFilters() {
        ApiClient admin = setupAndLoginAdmin();
        long id = ((Number) admin.post("/api/v1/filters", """
                {"name": "Old", "query": {"text": "a"}}""").json("$.id")).longValue();
        var updated = admin.patch("/api/v1/filters/" + id, """
                {"name": "New", "query": {"text": "b", "sort": "KEY"}, "sharedWith": "LOGGED_IN"}""");
        assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
        assertThat((String) updated.json("$.name")).isEqualTo("New");
        assertThat((String) updated.json("$.query.text")).isEqualTo("b");
        assertThat((String) updated.json("$.query.sort")).isEqualTo("KEY");
        assertThat((String) updated.json("$.sharedWith")).isEqualTo("LOGGED_IN");

        var missingProject = admin.patch("/api/v1/filters/" + id, "{\"sharedWith\": \"PROJECT\"}");
        assertThat((String) missingProject.json("$.code")).isEqualTo("filter.project-required");

        assertThat(admin.delete("/api/v1/filters/" + id).status()).isEqualTo(204);
        assertThat(admin.get("/api/v1/filters/" + id).status()).isEqualTo(404);
    }
}
