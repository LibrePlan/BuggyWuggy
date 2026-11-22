/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.board;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class BoardIntegrationTest extends IssueTestSupport {

    private static final String DEFAULT_CONFIG = """
            {"swimlanes": "NONE", "resolvedDays": 14, "columns": [
              {"name": "To Do", "statusIds": [%s]},
              {"name": "In Progress", "statusIds": [%s]},
              {"name": "Done", "statusIds": [%s]}]}""";

    /** The default board is not removed between tests: put it back as the migration made it. */
    @AfterEach
    void restoreDefaultBoard() {
        jdbc.update("update board set swimlanes = 'NONE', resolved_days = 14 where project_id is null");
        jdbc.update("delete from board_column where board_id = (select id from board where project_id is null)");
        jdbc.update("""
                insert into board_column (board_id, name, position)
                select b.id, c.name, c.position from board b
                cross join (values ('To Do', 0), ('In Progress', 1), ('Done', 2)) as c (name, position)
                where b.project_id is null""");
        jdbc.update("""
                insert into board_column_status (board_column_id, board_id, status_id)
                select c.id, c.board_id, s.id from board_column c
                join board b on b.id = c.board_id and b.project_id is null
                join status s on s.project_id is null and (
                                 (c.name = 'To Do' and s.category = 'TODO')
                              or (c.name = 'In Progress' and s.category = 'IN_PROGRESS')
                              or (c.name = 'Done' and s.category = 'DONE'))""");
    }

    private String statusIds(String category) {
        return String.join(", ", jdbc.queryForList("select id::text from status where category = ? order by id",
                String.class, category));
    }

    private long statusId(String name) {
        return jdbc.queryForObject("select id from status where name = ?", Long.class, name);
    }

    private long transitionId(ApiClient client, String key, String name) {
        List<Number> ids = client.get("/api/v1/issues/" + key + "/transitions").json("$[?(@.name == '" + name + "')].id");
        assertThat(ids).as("transition " + name).hasSize(1);
        return ids.get(0).longValue();
    }

    private List<String> keysIn(ApiClient client, String project, String column) {
        var board = client.get("/api/v1/projects/" + project + "/board");
        assertThat(board.status()).as(board.toString()).isEqualTo(200);
        List<Number> ids = board.json("$.columns[?(@.name == '" + column + "')].id");
        return board.json("$.issues[?(@.columnId == " + ids.get(0) + ")].key");
    }

    private void move(ApiClient client, String project, String json, int status) {
        var response = client.post("/api/v1/projects/" + project + "/board/moves", json);
        assertThat(response.status()).as(response.toString()).isEqualTo(status);
    }

    @Test
    void showsColumnsAndRecentIssues() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BRD", "Board");
        String open = createIssue(admin, "BRD", "Task", "Open");
        String recent = createIssue(admin, "BRD", "Task", "Resolved yesterday");
        String old = createIssue(admin, "BRD", "Task", "Resolved long ago");
        jdbc.update("""
                update issue set status_id = ?, resolution_id = ?, resolved_at = now() - interval '1 day'
                where number = 2""", statusId("Resolved"), resolutionId("Fixed"));
        jdbc.update("""
                update issue set status_id = ?, resolution_id = ?, resolved_at = now() - interval '30 days'
                where number = 3""", statusId("Closed"), resolutionId("Fixed"));

        var board = admin.get("/api/v1/projects/BRD/board");
        assertThat(board.status()).as(board.toString()).isEqualTo(200);
        assertThat((List<String>) board.json("$.columns[*].name")).containsExactly("To Do", "In Progress", "Done");
        assertThat((Boolean) board.json("$.ownColumns")).isFalse();
        assertThat((List<String>) board.json("$.issues[*].key")).containsExactly(open, recent).doesNotContain(old);
        assertThat(keysIn(admin, "BRD", "Done")).containsExactly(recent);
        assertThat((List<Object>) board.json("$.unmappedStatuses")).isEmpty();
        assertThat((Boolean) board.json("$.permissions.transition")).isTrue();

        // Like the project itself: not found for someone who may not browse it.
        createUser(admin, "otto", "otto-password-12");
        jdbc.update("delete from project_role_actor");
        assertThat(loginAs("otto", "otto-password-12").get("/api/v1/projects/BRD/board").status()).isEqualTo(404);
    }

    @Test
    void movesBetweenColumnsAndLanesAndRecordsHistory() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BRD", "Board");
        String a = createIssue(admin, "BRD", "Task", "A");
        String b = createIssue(admin, "BRD", "Task", "B");
        String c = createIssue(admin, "BRD", "Task", "C");
        long adminId = jdbc.queryForObject("select id from user_account where username = 'admin'", Long.class);
        jdbc.update("update issue set assignee_id = null");

        // To In Progress, to the admin's lane, and at the top: one move.
        move(admin, "BRD", """
                {"issue": "%s", "transitionId": %d, "lane": {"by": "ASSIGNEE", "value": "%d"}, "before": "%s"}"""
                .formatted(c, transitionId(admin, c, "Start Progress"), adminId, a), 204);
        var issue = admin.get("/api/v1/issues/" + c);
        assertThat((String) issue.json("$.status.name")).isEqualTo("In Progress");
        assertThat(((Number) issue.json("$.assignee.id")).longValue()).isEqualTo(adminId);
        List<String> fields = admin.get("/api/v1/issues/" + c + "/history").json("$[*].items[*].field");
        assertThat(fields).containsExactly("status", "assignee", "rank");
        assertThat((String) admin.get("/api/v1/issues/" + c + "/history").json("$[2].items[0].toText"))
                .isEqualTo("Ranked higher");
        assertThat((List<String>) admin.get("/api/v1/projects/BRD/board").json("$.issues[*].key"))
                .containsExactly(c, a, b);

        // Within a column: A below B.
        move(admin, "BRD", """
                {"issue": "%s", "after": "%s"}""".formatted(a, b), 204);
        assertThat(keysIn(admin, "BRD", "To Do")).containsExactly(b, a);
        // Between two issues, until the ranks are renumbered.
        for (int i = 0; i < 60; i++) {
            String moved = i % 2 == 0 ? b : a;
            move(admin, "BRD", """
                    {"issue": "%s", "after": "%s", "before": "%s"}""".formatted(moved, c, i % 2 == 0 ? a : b), 204);
        }
        assertThat((List<String>) admin.get("/api/v1/projects/BRD/board").json("$.issues[*].key"))
                .containsExactly(c, a, b);
        assertThat(jdbc.queryForObject("select count(distinct board_rank) from issue", Integer.class)).isEqualTo(3);

        // Back to the unassigned lane; the transition must be available.
        move(admin, "BRD", """
                {"issue": "%s", "lane": {"by": "ASSIGNEE", "value": null}}""".formatted(c), 204);
        assertThat((Object) admin.get("/api/v1/issues/" + c).json("$.assignee")).isNull();
        move(admin, "BRD", """
                {"issue": "%s", "transitionId": %d}""".formatted(c, transitionId(admin, a, "Start Progress")), 400);

        // By priority.
        long high = jdbc.queryForObject("select id from priority where name = 'High'", Long.class);
        move(admin, "BRD", """
                {"issue": "%s", "lane": {"by": "PRIORITY", "value": "%d"}}""".formatted(a, high), 204);
        assertThat((String) admin.get("/api/v1/issues/" + a).json("$.priority.name")).isEqualTo("High");
        move(admin, "BRD", """
                {"issue": "%s", "lane": {"by": "PRIORITY", "value": null}}""".formatted(a), 400);

        // A stale version changes nothing.
        move(admin, "BRD", """
                {"issue": "%s", "version": 0, "lane": {"by": "PRIORITY", "value": "%d"}}""".formatted(a, high), 409);
    }

    @Test
    void movesToAnotherEpic() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BRD", "Board");
        String epic = createIssue(admin, "BRD", "Epic", "Epic");
        String task = createIssue(admin, "BRD", "Task", "Task");
        String subtask = createIssue(admin, "BRD", "Sub-task", "Sub-task", task);

        move(admin, "BRD", """
                {"issue": "%s", "lane": {"by": "EPIC", "value": "%s"}}""".formatted(task, epic), 204);
        assertThat((String) admin.get("/api/v1/issues/" + task).json("$.parent.key")).isEqualTo(epic);
        var board = admin.get("/api/v1/projects/BRD/board");
        assertThat((List<String>) board.json("$.issues[?(@.key == '" + subtask + "')].epic.key"))
                .containsExactly(epic);
        assertThat((List<String>) board.json("$.issues[?(@.key == '" + epic + "')].epic.key")).containsExactly(epic);
        assertThat((List<String>) admin.get("/api/v1/issues/" + task + "/history").json("$[*].items[*].field"))
                .containsExactly("parent");

        // Sub-tasks belong to the epic of their parent.
        move(admin, "BRD", """
                {"issue": "%s", "lane": {"by": "EPIC", "value": null}}""".formatted(subtask), 400);
        move(admin, "BRD", """
                {"issue": "%s", "lane": {"by": "EPIC", "value": null}}""".formatted(task), 204);
        assertThat((Object) admin.get("/api/v1/issues/" + task).json("$.parent")).isNull();
    }

    @Test
    void configuresColumnsPerProjectAndByDefault() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BRD", "Board");
        createProject(admin, "OTH", "Other");
        String issue = createIssue(admin, "BRD", "Task", "In review");

        var own = admin.put("/api/v1/projects/BRD/board/config", """
                {"swimlanes": "ASSIGNEE", "resolvedDays": 7, "columns": [
                  {"name": "Backlog", "statusIds": [%d]},
                  {"name": "Doing", "statusIds": [%d, %d]}]}""".formatted(statusId("Open"),
                statusId("In Progress"), statusId("Reopened")));
        assertThat(own.status()).as(own.toString()).isEqualTo(200);
        assertThat((Boolean) own.json("$.own")).isTrue();
        var board = admin.get("/api/v1/projects/BRD/board");
        assertThat((List<String>) board.json("$.columns[*].name")).containsExactly("Backlog", "Doing");
        assertThat((String) board.json("$.swimlanes")).isEqualTo("ASSIGNEE");
        assertThat((List<String>) board.json("$.unmappedStatuses[*].name")).containsExactly("Closed", "Resolved");
        assertThat(keysIn(admin, "BRD", "Backlog")).containsExactly(issue);
        assertThat((List<String>) admin.get("/api/v1/projects/OTH/board").json("$.columns[*].name"))
                .containsExactly("To Do", "In Progress", "Done");

        // A status in two columns, or two columns with the same name.
        assertThat(admin.put("/api/v1/projects/BRD/board/config", """
                {"swimlanes": "NONE", "resolvedDays": 7, "columns": [
                  {"name": "A", "statusIds": [%1$d]}, {"name": "B", "statusIds": [%1$d]}]}"""
                .formatted(statusId("Open"))).status()).isEqualTo(400);
        assertThat(admin.put("/api/v1/projects/BRD/board/config", """
                {"swimlanes": "NONE", "resolvedDays": 7, "columns": [
                  {"name": "A", "statusIds": []}, {"name": "a", "statusIds": []}]}""").status()).isEqualTo(400);

        // The administrator's default, for projects without columns of their own.
        var saved = admin.put("/api/v1/admin/board", DEFAULT_CONFIG.formatted(statusIds("TODO"),
                statusIds("IN_PROGRESS"), statusIds("DONE")).replace("\"Done\"", "\"Finished\""));
        assertThat(saved.status()).as(saved.toString()).isEqualTo(200);
        assertThat((List<String>) admin.get("/api/v1/projects/OTH/board").json("$.columns[*].name"))
                .containsExactly("To Do", "In Progress", "Finished");
        assertThat((List<String>) admin.get("/api/v1/projects/BRD/board").json("$.columns[*].name"))
                .containsExactly("Backlog", "Doing");

        var reset = admin.delete("/api/v1/projects/BRD/board/config");
        assertThat(reset.status()).as(reset.toString()).isEqualTo(200);
        assertThat((Boolean) reset.json("$.own")).isFalse();
        assertThat((List<String>) admin.get("/api/v1/projects/BRD/board").json("$.columns[*].name"))
                .containsExactly("To Do", "In Progress", "Finished");

        // Only administrators change the default; project administrators their project's board.
        createUser(admin, "otto", "otto-password-12");
        ApiClient otto = loginAs("otto", "otto-password-12");
        assertThat(otto.get("/api/v1/admin/board").status()).isEqualTo(403);
        assertThat(otto.get("/api/v1/projects/BRD/board/config").status()).isIn(403, 404);
    }

    @Test
    void managesProjectStatusesAndReassignsIssuesOnDeletion() {
        ApiClient admin = setupAndLoginAdmin();
        long project = createProject(admin, "BRD", "Board");
        createProject(admin, "OTH", "Other");
        String issue = createIssue(admin, "BRD", "Task", "Needs review");
        String otherIssue = createIssue(admin, "OTH", "Task", "Other project");
        String config = "/api/v1/projects/BRD/board/config";
        long open = statusId("Open");
        long progress = statusId("In Progress");
        String base = """
                {"swimlanes": "NONE", "resolvedDays": 14, "columns": [
                  {"name": "To Do", "statusIds": [%d]},
                  {"name": "In Progress", "statusIds": [%d]}""".formatted(open, progress).stripTrailing();

        var created = admin.put(config, base + """
                , {"name": "Review", "statusIds": [-1, -2]}],
                  "customStatuses": [{"id": -1, "name": "Review"}, {"id": -2, "name": "Test"}]}""");
        assertThat(created.status()).as(created.toString()).isEqualTo(200);
        List<Number> custom = created.json("$.customStatusIds");
        assertThat(custom).hasSize(2);
        long review = custom.get(0).longValue();
        long test = custom.get(1).longValue();

        assertThat((List<String>) admin.get("/api/v1/issues/" + otherIssue + "/transitions").json("$[*].to.name"))
                .doesNotContain("Review", "Test");
        assertThat(admin.put("/api/v1/projects/OTH/board/config", base + """
                , {"name": "Review", "statusIds": [%d]}]}""".formatted(review)).status()).isEqualTo(400);
        assertThat(admin.delete(config).status()).isEqualTo(400);
        assertThat((List<String>) admin.get("/api/v1/projects/BRD/board")
                .json("$.columns[?(@.name == 'Review')].statuses[*].name")).containsExactly("Review", "Test");

        move(admin, "BRD", """
                {"issue": "%s", "transitionId": %d}""".formatted(issue, transitionId(admin, issue, "Review")), 204);
        assertThat(keysIn(admin, "BRD", "Review")).containsExactly(issue);
        assertThat(transitionId(admin, issue, "Test")).isPositive();
        assertThat(transitionId(admin, issue, "Resolve Issue")).isPositive();

        var updated = admin.put(config, base + """
                , {"name": "Quality", "statusIds": [%1$d, %2$d]}],
                  "customStatuses": [{"id": %1$d, "name": "Test"}, {"id": %2$d, "name": "Code Review"}]}"""
                .formatted(test, review));
        assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
        assertThat((List<Number>) admin.get(config).json("$.customStatusIds"))
                .containsExactly(custom.get(1), custom.get(0));
        assertThat((List<String>) admin.get("/api/v1/projects/BRD/board").json("$.columns[*].name"))
                .containsExactly("To Do", "In Progress", "Quality");
        assertThat((List<String>) admin.get("/api/v1/projects/BRD/board")
                .json("$.columns[?(@.name == 'Quality')].statuses[*].name")).containsExactly("Test", "Code Review");
        assertThat(keysIn(admin, "BRD", "Quality")).containsExactly(issue);
        assertThat((String) admin.get("/api/v1/issues/" + issue).json("$.status.name")).isEqualTo("Code Review");

        // Removing a column leaves its workflow statuses and issues intact.
        var unmapped = admin.put(config, base + "]}");
        assertThat(unmapped.status()).as(unmapped.toString()).isEqualTo(200);
        assertThat((List<Number>) unmapped.json("$.customStatusIds")).containsExactly(custom.get(1), custom.get(0));
        assertThat((String) admin.get("/api/v1/issues/" + issue).json("$.status.name")).isEqualTo("Code Review");

        String removing = base + "], \"removedStatusIds\": [%d, %d]".formatted(review, test);
        assertThat(admin.put(config, removing + "}").status()).isEqualTo(400);
        assertThat((String) admin.get("/api/v1/issues/" + issue).json("$.status.name")).isEqualTo("Code Review");
        String replacement = ", \"statusReplacements\": {\"%d\": %%d}}".formatted(review);
        assertThat(admin.put(config, removing + replacement.formatted(test)).status()).isEqualTo(400);
        var deleted = admin.put(config, removing + replacement.formatted(open));
        assertThat(deleted.status()).as(deleted.toString()).isEqualTo(200);
        assertThat((List<Number>) deleted.json("$.customStatusIds")).isEmpty();
        assertThat(keysIn(admin, "BRD", "To Do")).containsExactly(issue);
        assertThat((List<String>) admin.get("/api/v1/issues/" + issue + "/history").json("$[*].items[*].toText"))
                .contains("Open");
        assertThat(admin.delete(config).status()).isEqualTo(200);

        jdbc.update("delete from project where id = ?", project);
        assertThat(jdbc.queryForObject("select count(*) from workflow where project_id = ?", Integer.class, project))
                .isZero();
    }
}
