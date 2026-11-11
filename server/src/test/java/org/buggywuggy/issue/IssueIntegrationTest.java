package org.buggywuggy.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

class IssueIntegrationTest extends IntegrationTest {

    // --- creating ----------------------------------------------------------------------

    @Test
    void createsIssueWithDefaultsAndSequentialKeys() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");

        var created = admin.post("/api/v1/issues", """
                {"project": "BUG", "issueTypeId": %d, "summary": "  Login fails  "}""".formatted(typeId("Bug")));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat((String) created.json("$.key")).isEqualTo("BUG-1");
        assertThat((String) created.json("$.summary")).isEqualTo("Login fails");
        assertThat((String) created.json("$.status.name")).isEqualTo("Open");
        assertThat((String) created.json("$.priority.name")).isEqualTo("Medium");
        assertThat((String) created.json("$.reporter.username")).isEqualTo(ADMIN);
        assertThat((String) created.json("$.creator.username")).isEqualTo(ADMIN);
        assertThat(created.json("$.assignee") == null).isTrue();

        assertThat(createIssue(admin, "BUG", "Second")).isEqualTo("BUG-2");
        createProject(admin, "WEB", "Website");
        assertThat(createIssue(admin, "WEB", "Other project")).isEqualTo("WEB-1");
    }

    @Test
    void createsIssueWithAllFields() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long backend = createComponent(admin, "BUG", "Backend");
        long v1 = createVersion(admin, "BUG", "1.0");
        long v2 = createVersion(admin, "BUG", "2.0");
        long adminId = myId(admin);

        var created = admin.post("/api/v1/issues", """
                {"project": "BUG", "issueTypeId": %d, "summary": "Crash", "description": "Steps...",
                 "priorityId": %d, "assigneeId": %d, "labels": ["ui", "crash", "ui"],
                 "componentIds": [%d], "affectsVersionIds": [%d], "fixVersionIds": [%d], "dueDate": "2026-12-31"}"""
                .formatted(typeId("Bug"), priorityId("High"), adminId, backend, v1, v2));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat((String) created.json("$.priority.name")).isEqualTo("High");
        assertThat((String) created.json("$.assignee.username")).isEqualTo(ADMIN);
        assertThat((List<String>) created.json("$.labels")).containsExactly("crash", "ui");
        assertThat((List<String>) created.json("$.components[*].name")).containsExactly("Backend");
        assertThat((List<String>) created.json("$.affectsVersions[*].name")).containsExactly("1.0");
        assertThat((List<String>) created.json("$.fixVersions[*].name")).containsExactly("2.0");
        assertThat((String) created.json("$.dueDate")).isEqualTo("2026-12-31");

        // Reporter and creator watch the issue.
        assertThat(jdbc.queryForObject("select count(*) from watcher", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsInvalidInput() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "WEB", "Website");
        long webComponent = createComponent(admin, "WEB", "Frontend");

        assertThat((String) admin.post("/api/v1/issues", """
                {"project": "BUG", "issueTypeId": %d, "summary": "x", "componentIds": [%d]}"""
                .formatted(typeId("Bug"), webComponent)).json("$.code")).isEqualTo("issue.component-invalid");
        assertThat((String) admin.post("/api/v1/issues", """
                {"project": "BUG", "issueTypeId": %d, "summary": "x"}"""
                .formatted(typeId("Sub-task"))).json("$.code")).isEqualTo("issue.subtask-requires-parent");
        assertThat((String) admin.post("/api/v1/issues", """
                {"project": "BUG", "issueTypeId": %d, "summary": "x", "labels": ["two words"]}"""
                .formatted(typeId("Bug"))).json("$.errors[0].field")).isEqualTo("labels[0]");
        assertThat(admin.post("/api/v1/issues", """
                {"project": "BUG", "issueTypeId": %d, "summary": ""}""".formatted(typeId("Bug"))).status())
                .isEqualTo(400);
    }

    @Test
    void appliesDefaultAssignees() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long devId = createUser(admin, "vera", "vera-password-12");
        admin.put("/api/v1/projects/BUG/roles/" + roleId("Developers") + "/users/" + devId, null);

        admin.patch("/api/v1/projects/BUG", """
                {"defaultAssignee": "PROJECT_LEAD"}""");
        assertThat((String) admin.get("/api/v1/issues/" + createIssue(admin, "BUG", "To lead"))
                .json("$.assignee.username")).isEqualTo(ADMIN);

        long component = ((Number) admin.post("/api/v1/projects/BUG/components", """
                {"name": "Backend", "leadId": %d, "defaultAssignee": "COMPONENT_LEAD"}""".formatted(devId))
                .json("$.id")).longValue();
        var issue = admin.post("/api/v1/issues", """
                {"project": "BUG", "issueTypeId": %d, "summary": "To component lead", "componentIds": [%d]}"""
                .formatted(typeId("Bug"), component));
        assertThat((String) issue.json("$.assignee.username")).isEqualTo("vera");
    }

    // --- permissions -------------------------------------------------------------------

    @Test
    void reportersCanCreateButNotEditOrAssign() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createUser(admin, "walt", "walt-password-12");
        ApiClient walt = loginAs("walt", "walt-password-12");

        String key = createIssue(walt, "BUG", "Reported by Walt");
        assertThat((String) walt.get("/api/v1/issues/" + key).json("$.reporter.username")).isEqualTo("walt");

        assertThat(walt.patch("/api/v1/issues/" + key, """
                {"summary": "Changed"}""").status()).isEqualTo(403);
        assertThat(walt.patch("/api/v1/issues/" + key, """
                {"assigneeId": %d}""".formatted(myId(admin))).status()).isEqualTo(403);
        assertThat(walt.delete("/api/v1/issues/" + key).status()).isEqualTo(403);
    }

    @Test
    void onlyAssignableUsersCanBeAssigned() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long reporterOnly = createUser(admin, "xena", "xena-password-12");
        String key = createIssue(admin, "BUG", "Needs an owner");

        var response = admin.patch("/api/v1/issues/" + key, """
                {"assigneeId": %d}""".formatted(reporterOnly));
        assertThat(response.status()).isEqualTo(400);
        assertThat((String) response.json("$.code")).isEqualTo("issue.assignee-not-assignable");
    }

    @Test
    void issuesOfInvisibleProjectsAreNotFound() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "SECRET", "Secret");
        String key = createIssue(admin, "SECRET", "Hidden");
        admin.delete("/api/v1/projects/SECRET/roles/" + roleId("Users") + "/groups/" + groupId("users"));
        createUser(admin, "yuri", "yuri-password-12");

        var response = loginAs("yuri", "yuri-password-12").get("/api/v1/issues/" + key);
        assertThat(response.status()).isEqualTo(404);
        assertThat((String) response.json("$.code")).isEqualTo("issue.not-found");
    }

    @Test
    void setSomeoneElseAsReporterRequiresProjectAdministration() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long devId = createUser(admin, "zack", "zack-password-12");
        admin.put("/api/v1/projects/BUG/roles/" + roleId("Developers") + "/users/" + devId, null);
        ApiClient zack = loginAs("zack", "zack-password-12");
        String key = createIssue(admin, "BUG", "Reporter test");

        assertThat((String) zack.patch("/api/v1/issues/" + key, """
                {"reporterId": %d}""".formatted(devId)).json("$.code")).isEqualTo("issue.reporter-permission");
        assertThat((String) admin.patch("/api/v1/issues/" + key, """
                {"reporterId": %d}""".formatted(devId)).json("$.reporter.username")).isEqualTo("zack");
    }

    // --- updating and history ----------------------------------------------------------

    @Test
    void updatesAreRecordedInHistory() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long backend = createComponent(admin, "BUG", "Backend");
        long api = createComponent(admin, "BUG", "API");
        long v1 = createVersion(admin, "BUG", "1.0");
        var created = admin.post("/api/v1/issues", """
                {"project": "BUG", "issueTypeId": %d, "summary": "Original", "labels": ["a"],
                 "componentIds": [%d]}""".formatted(typeId("Bug"), backend));
        String key = created.json("$.key");

        var updated = admin.patch("/api/v1/issues/" + key, """
                {"summary": "Changed", "priorityId": %d, "labels": ["b", "a"], "componentIds": [%d],
                 "fixVersionIds": [%d], "assigneeId": %d, "dueDate": "2027-01-15"}"""
                .formatted(priorityId("Highest"), api, v1, myId(admin)));
        assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
        assertThat((String) updated.json("$.summary")).isEqualTo("Changed");
        assertThat((List<String>) updated.json("$.components[*].name")).containsExactly("API");

        var history = admin.get("/api/v1/issues/" + key + "/history");
        assertThat((List<Object>) history.json("$")).hasSize(1);
        assertThat((String) history.json("$[0].author.username")).isEqualTo(ADMIN);
        List<Map<String, Object>> items = history.json("$[0].items");
        assertThat(items).extracting(i -> i.get("field")).containsExactlyInAnyOrder(
                "summary", "priority", "assignee", "duedate", "labels", "component", "component", "fixversion");
        assertThat(items).anySatisfy(i -> {
            assertThat(i.get("field")).isEqualTo("summary");
            assertThat(i.get("fromText")).isEqualTo("Original");
            assertThat(i.get("toText")).isEqualTo("Changed");
        });
        assertThat(items).anySatisfy(i -> {
            assertThat(i.get("field")).isEqualTo("labels");
            assertThat(i.get("fromText")).isEqualTo("a");
            assertThat(i.get("toText")).isEqualTo("a b");
        });
        assertThat(items).anySatisfy(i -> {
            assertThat(i.get("field")).isEqualTo("component");
            assertThat(i.get("fromText")).isEqualTo("Backend");
            assertThat(i.get("to")).isNull();
        });
    }

    @Test
    void updateWithoutChangesLeavesNoTrace() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Stable");
        String updatedAt = admin.get("/api/v1/issues/" + key).json("$.updatedAt");

        var response = admin.patch("/api/v1/issues/" + key, """
                {"summary": "Stable", "labels": []}""");
        assertThat((String) response.json("$.updatedAt")).isEqualTo(updatedAt);
        assertThat((List<Object>) admin.get("/api/v1/issues/" + key + "/history").json("$")).isEmpty();
    }

    @Test
    void concurrentEditsAreDetectedWithVersion() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Shared");
        int version = admin.get("/api/v1/issues/" + key).json("$.version");

        var first = admin.patch("/api/v1/issues/" + key, """
                {"version": %d, "summary": "First editor"}""".formatted(version));
        assertThat(first.status()).isEqualTo(200);
        int newVersion = first.json("$.version");
        assertThat(newVersion).isGreaterThan(version);

        var stale = admin.patch("/api/v1/issues/" + key, """
                {"version": %d, "summary": "Second editor"}""".formatted(version));
        assertThat(stale.status()).isEqualTo(409);
        assertThat((String) stale.json("$.code")).isEqualTo("error.conflict");

        assertThat(admin.patch("/api/v1/issues/" + key, """
                {"version": %d, "summary": "Second editor, after reload"}""".formatted(newVersion)).status())
                .isEqualTo(200);
    }

    @Test
    void issueTypeChangeIsRecorded() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Typed");

        assertThat((String) admin.patch("/api/v1/issues/" + key, """
                {"issueTypeId": %d}""".formatted(typeId("Task"))).json("$.issueType.name")).isEqualTo("Task");
        assertThat((List<String>) admin.get("/api/v1/issues/" + key + "/history").json("$[0].items[*].toText"))
                .containsExactly("Task");
    }

    // --- keys, archive, delete, listing -------------------------------------------------

    @Test
    void issueKeysFollowProjectKeyChanges() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createIssue(admin, "BUG", "Old key");
        admin.patch("/api/v1/projects/BUG", """
                {"key": "BT"}""");

        assertThat((String) admin.get("/api/v1/issues/BUG-1").json("$.key")).isEqualTo("BT-1");
        assertThat((String) admin.get("/api/v1/issues/bt-1").json("$.key")).isEqualTo("BT-1");
        assertThat(admin.get("/api/v1/issues/BT-99").status()).isEqualTo(404);
    }

    @Test
    void archivedProjectIssuesAreReadOnly() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "OLD", "Old project");
        String key = createIssue(admin, "OLD", "Before archiving");
        admin.patch("/api/v1/projects/OLD", """
                {"archived": true}""");

        assertThat(admin.get("/api/v1/issues/" + key).status()).isEqualTo(200);
        assertThat((String) admin.patch("/api/v1/issues/" + key, """
                {"summary": "After"}""").json("$.code")).isEqualTo("project.archived");
        assertThat((String) admin.post("/api/v1/issues", """
                {"project": "OLD", "issueTypeId": %d, "summary": "New"}""".formatted(typeId("Bug")))
                .json("$.code")).isEqualTo("project.archived");
    }

    @Test
    void deletedIssueNumbersAreNotReused() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Short-lived");

        assertThat(admin.delete("/api/v1/issues/" + key).status()).isEqualTo(204);
        assertThat(admin.get("/api/v1/issues/" + key).status()).isEqualTo(404);
        assertThat(createIssue(admin, "BUG", "Next")).isEqualTo("BUG-2");
    }

    @Test
    void developersCannotDeleteIssues() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long devId = createUser(admin, "amy", "amy-password-123");
        admin.put("/api/v1/projects/BUG/roles/" + roleId("Developers") + "/users/" + devId, null);
        String key = createIssue(admin, "BUG", "Keep me");

        ApiClient amy = loginAs("amy", "amy-password-123");
        assertThat(amy.patch("/api/v1/issues/" + key, """
                {"summary": "Edited by developer"}""").status()).isEqualTo(200);
        assertThat(amy.delete("/api/v1/issues/" + key).status()).isEqualTo(403);
    }

    @Test
    void listsProjectIssuesNewestFirst() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createIssue(admin, "BUG", "First");
        createIssue(admin, "BUG", "Second");
        createIssue(admin, "BUG", "Third");

        var page = admin.get("/api/v1/projects/BUG/issues?size=2");
        assertThat((List<String>) page.json("$.items[*].key")).containsExactly("BUG-3", "BUG-2");
        assertThat((Integer) page.json("$.total")).isEqualTo(3);
    }

    @Test
    void lookupListsAreAvailable() {
        ApiClient admin = setupAndLoginAdmin();
        assertThat((List<String>) admin.get("/api/v1/issue-types").json("$[*].name"))
                .containsExactly("Epic", "Story", "Task", "Bug", "Sub-task");
        assertThat((List<String>) admin.get("/api/v1/priorities").json("$[*].name"))
                .containsExactly("Highest", "High", "Medium", "Low", "Lowest");
        assertThat((List<String>) admin.get("/api/v1/statuses").json("$[*].category"))
                .containsExactly("TODO", "IN_PROGRESS", "TODO", "DONE", "DONE");
        assertThat((List<String>) admin.get("/api/v1/resolutions").json("$[*].name")).contains("Fixed", "Done");
    }

    // --- helpers -----------------------------------------------------------------------

    private String createIssue(ApiClient client, String project, String summary) {
        var response = client.post("/api/v1/issues", """
                {"project": "%s", "issueTypeId": %d, "summary": "%s"}""".formatted(project, typeId("Bug"), summary));
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return response.json("$.key");
    }

    private long createComponent(ApiClient client, String project, String name) {
        return ((Number) client.post("/api/v1/projects/" + project + "/components", """
                {"name": "%s"}""".formatted(name)).json("$.id")).longValue();
    }

    private long createVersion(ApiClient client, String project, String name) {
        return ((Number) client.post("/api/v1/projects/" + project + "/versions", """
                {"name": "%s"}""".formatted(name)).json("$.id")).longValue();
    }

    private long typeId(String name) {
        return jdbc.queryForObject("select id from issue_type where name = ?", Long.class, name);
    }

    private long priorityId(String name) {
        return jdbc.queryForObject("select id from priority where name = ?", Long.class, name);
    }

    private long myId(ApiClient client) {
        return ((Number) client.get("/api/v1/myself").json("$.id")).longValue();
    }
}
