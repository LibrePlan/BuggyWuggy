/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.taskwarrior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Urgency in search results and per issue, with coefficients from the configuration. */
@TestPropertySource(properties = {
        "buggywuggy.taskwarrior.urgency.priority.High=7.0",
        "buggywuggy.taskwarrior.urgency.projects.SHOP=0.5"})
class TaskWarriorIntegrationTest extends IssueTestSupport {

    @Test
    @SuppressWarnings("unchecked")
    void sortsByUrgencyAndExplainsIt() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "SHOP", "Shop");
        String old = createIssue(admin, "SHOP", "Task", "Old and important");
        String blocked = createIssue(admin, "SHOP", "Task", "Waits for the first");
        String done = createIssue(admin, "SHOP", "Task", "Finished");
        long linkType = jdbc.queryForObject("select id from issue_link_type where name = 'Blocks'", Long.class);
        assertThat(admin.post("/api/v1/issues/" + old + "/links", """
                {"linkTypeId": %d, "issue": "%s"}""".formatted(linkType, blocked)).status()).isEqualTo(201);
        long high = jdbc.queryForObject("select id from priority where name = 'High'", Long.class);
        jdbc.update("update issue set created_at = now() - interval '400 days', priority_id = ? where number = 1",
                high);
        jdbc.update("insert into issue_label (issue_id, label) select id, 'next' from issue where number = 1");
        jdbc.update("update issue set priority_id = null where number = 2");
        jdbc.update("update issue set resolution_id = ? where number = 3", resolutionId("Done"));

        var page = admin.post("/api/v1/issues/search", """
                {"projectIds": [%d], "sort": "URGENCY"}""".formatted(
                jdbc.queryForObject("select id from project where key = 'SHOP'", Long.class)));
        assertThat(page.status()).as(page.toString()).isEqualTo(200);
        assertThat((List<String>) page.json("$.items[*].key")).containsExactly(old, blocked, done);
        // age 2 + tags 0.8 + blocking 8 + priority High 7 (configured) + project SHOP 0.5 + tag next 15
        assertThat(((Number) page.json("$.items[0].urgency")).doubleValue()).isCloseTo(33.3, within(0.01));
        // blocked -5 + project SHOP 0.5, and a few seconds of age
        assertThat(((Number) page.json("$.items[1].urgency")).doubleValue()).isCloseTo(-4.5, within(0.01));
        assertThat((Object) page.json("$.items[2].urgency")).isNull();

        var ascending = admin.post("/api/v1/issues/search", """
                {"sort": "URGENCY", "direction": "ASC"}""");
        assertThat((List<String>) ascending.json("$.items[*].key")).containsExactly(blocked, old, done);

        var explained = admin.get("/api/v1/issues/" + old + "/urgency");
        assertThat(explained.status()).as(explained.toString()).isEqualTo(200);
        assertThat((List<String>) explained.json("$.terms[*].name")).containsExactly("tags", "age", "blocking",
                "priority High", "project SHOP", "tag next");
        assertThat((List<Double>) explained.json("$.terms[?(@.name == 'tags')].factor")).containsExactly(0.8);
        assertThat((Boolean) explained.json("$.resolved")).isFalse();
        var resolved = admin.get("/api/v1/issues/" + done + "/urgency");
        assertThat((Boolean) resolved.json("$.resolved")).isTrue();
        assertThat((Object) resolved.json("$.urgency")).isNull();

        // Like the issue itself: not found for someone who may not browse the project.
        createUser(admin, "otto", "otto-password-12");
        jdbc.update("delete from project_role_actor");
        assertThat(loginAs("otto", "otto-password-12").get("/api/v1/issues/" + old + "/urgency").status())
                .isEqualTo(404);
    }
}
