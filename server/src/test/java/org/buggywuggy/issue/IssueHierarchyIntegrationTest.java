package org.buggywuggy.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.junit.jupiter.api.Test;

/** Epics, standard issues and sub-tasks. */
class IssueHierarchyIntegrationTest extends IssueTestSupport {

    @Test
    void subTasksBelongToAStandardIssue() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String parent = createIssue(admin, "BUG", "Story", "Parent story");
        String subtask = createIssue(admin, "BUG", "Sub-task", "Do a part", parent);

        assertThat((String) admin.get("/api/v1/issues/" + subtask).json("$.parent.key")).isEqualTo(parent);
        var parentIssue = admin.get("/api/v1/issues/" + parent);
        assertThat((List<String>) parentIssue.json("$.children[*].key")).containsExactly(subtask);
        assertThat((List<String>) parentIssue.json("$.children[*].status.name")).containsExactly("Open");
    }

    @Test
    void enforcesHierarchyRulesOnCreate() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "WEB", "Website");
        String story = createIssue(admin, "BUG", "Story", "Story");
        String subtask = createIssue(admin, "BUG", "Sub-task", "Sub-task", story);
        String epic = createIssue(admin, "BUG", "Epic", "Epic");
        String otherProject = createIssue(admin, "WEB", "Story", "Elsewhere");

        assertThat(code(admin, "Sub-task", null)).isEqualTo("issue.subtask-requires-parent");
        assertThat(code(admin, "Sub-task", subtask)).isEqualTo("issue.parent-invalid");
        assertThat(code(admin, "Sub-task", epic)).isEqualTo("issue.parent-invalid");
        assertThat(code(admin, "Story", story)).isEqualTo("issue.parent-invalid");
        assertThat(code(admin, "Epic", epic)).isEqualTo("issue.parent-not-allowed");
        assertThat(code(admin, "Sub-task", otherProject)).isEqualTo("issue.parent-other-project");
        assertThat(code(admin, "Sub-task", "BUG-999")).isEqualTo("issue.parent-not-found");

        // A standard issue can be created inside an epic.
        assertThat(createIssue(admin, "BUG", "Story", "In epic", epic)).isNotNull();
    }

    @Test
    void issuesMoveInAndOutOfEpicsWithHistory() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String epic = createIssue(admin, "BUG", "Epic", "Big feature");
        String story = createIssue(admin, "BUG", "Story", "Part of it");

        assertThat((String) admin.patch("/api/v1/issues/" + story, """
                {"parent": "%s"}""".formatted(epic)).json("$.parent.key")).isEqualTo(epic);
        assertThat((List<String>) admin.get("/api/v1/issues/" + epic).json("$.children[*].key"))
                .containsExactly(story);

        var cleared = admin.patch("/api/v1/issues/" + story, """
                {"clearParent": true}""");
        assertThat(cleared.json("$.parent") == null).isTrue();

        var history = admin.get("/api/v1/issues/" + story + "/history");
        assertThat((List<String>) history.json("$[*].items[0].field")).containsExactly("parent", "parent");
        assertThat((String) history.json("$[0].items[0].toText")).isEqualTo(epic);
        assertThat((String) history.json("$[1].items[0].fromText")).isEqualTo(epic);
    }

    @Test
    void convertsBetweenTaskAndSubTask() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String story = createIssue(admin, "BUG", "Story", "Story");
        String task = createIssue(admin, "BUG", "Task", "Task");

        // Task -> sub-task needs a parent in the same request.
        assertThat((String) admin.patch("/api/v1/issues/" + task, """
                {"issueTypeId": %d}""".formatted(typeId("Sub-task"))).json("$.code"))
                .isEqualTo("issue.subtask-requires-parent");
        var converted = admin.patch("/api/v1/issues/" + task, """
                {"issueTypeId": %d, "parent": "%s"}""".formatted(typeId("Sub-task"), story));
        assertThat((String) converted.json("$.issueType.name")).isEqualTo("Sub-task");
        assertThat((String) converted.json("$.parent.key")).isEqualTo(story);

        // Sub-task -> task needs its parent removed.
        assertThat((String) admin.patch("/api/v1/issues/" + task, """
                {"issueTypeId": %d}""".formatted(typeId("Task"))).json("$.code")).isEqualTo("issue.parent-invalid");
        var back = admin.patch("/api/v1/issues/" + task, """
                {"issueTypeId": %d, "clearParent": true}""".formatted(typeId("Task")));
        assertThat((String) back.json("$.issueType.name")).isEqualTo("Task");
        assertThat(back.json("$.parent") == null).isTrue();
    }

    @Test
    void issueWithChildrenCannotChangeLevel() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String story = createIssue(admin, "BUG", "Story", "Has sub-tasks");
        createIssue(admin, "BUG", "Sub-task", "Child", story);

        assertThat((String) admin.patch("/api/v1/issues/" + story, """
                {"issueTypeId": %d}""".formatted(typeId("Epic"))).json("$.code"))
                .isEqualTo("issue.type-change-has-children");
        // Same level is fine.
        assertThat(admin.patch("/api/v1/issues/" + story, """
                {"issueTypeId": %d}""".formatted(typeId("Task"))).status()).isEqualTo(200);
    }

    @Test
    void deletingIssuesHandlesChildren() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String epic = createIssue(admin, "BUG", "Epic", "Epic");
        String story = createIssue(admin, "BUG", "Story", "Story", epic);
        String subtask = createIssue(admin, "BUG", "Sub-task", "Sub-task", story);

        // Deleting a story deletes its sub-tasks.
        assertThat(admin.delete("/api/v1/issues/" + story).status()).isEqualTo(204);
        assertThat(admin.get("/api/v1/issues/" + subtask).status()).isEqualTo(404);

        // Deleting an epic keeps its issues, without parent.
        String other = createIssue(admin, "BUG", "Story", "Other story", epic);
        assertThat(admin.delete("/api/v1/issues/" + epic).status()).isEqualTo(204);
        var orphan = admin.get("/api/v1/issues/" + other);
        assertThat(orphan.status()).isEqualTo(200);
        assertThat(orphan.json("$.parent") == null).isTrue();
    }

    private String code(ApiClient client, String type, String parent) {
        var response = client.post("/api/v1/issues", """
                {"project": "BUG", "issueTypeId": %d, "summary": "x"%s}""".formatted(typeId(type),
                parent == null ? "" : ", \"parent\": \"" + parent + "\""));
        assertThat(response.status()).as(response.toString()).isEqualTo(400);
        return response.json("$.code");
    }
}
