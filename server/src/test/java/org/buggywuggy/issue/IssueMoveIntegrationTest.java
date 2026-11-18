package org.buggywuggy.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.junit.jupiter.api.Test;

/** Moving an issue to another project: the issue moves, a closed stub stays behind. */
class IssueMoveIntegrationTest extends IssueTestSupport {

    @Test
    void movesTheIssueAndLeavesAStub() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "SUP", "Support");
        createIssue(admin, "SUP", "Task", "Already there");
        String key = createIssue(admin, "BUG", "Bug", "Printer on fire");
        var comment = admin.post("/api/v1/issues/" + key + "/comments", """
                {"body": "It smells."}""");
        assertThat(comment.status()).isEqualTo(201);
        String other = createIssue(admin, "BUG", "Task", "Buy extinguisher");
        admin.post("/api/v1/issues/" + other + "/links", """
                {"issue": "%s", "linkTypeId": %d}""".formatted(key, linkTypeId("Relates")));
        long id = ((Number) admin.get("/api/v1/issues/" + key).json("$.id")).longValue();

        var options = admin.get("/api/v1/issues/" + key + "/move");
        assertThat((Boolean) options.json("$.movable")).isTrue();
        assertThat((List<String>) options.json("$.targets[*].key")).containsExactly("SUP");

        var moved = admin.post("/api/v1/issues/" + key + "/move", """
                {"project": "SUP"}""");
        assertThat(moved.status()).as(moved.toString()).isEqualTo(200);
        assertThat((String) moved.json("$.key")).isEqualTo("SUP-2");
        assertThat(((Number) moved.json("$.id")).longValue()).isEqualTo(id);
        assertThat((String) moved.json("$.project.key")).isEqualTo("SUP");

        // The comment and the link moved along with the issue.
        assertThat((List<String>) admin.get("/api/v1/issues/SUP-2/comments").json("$[*].body"))
                .containsExactly("It smells.");
        assertThat((List<String>) admin.get("/api/v1/issues/" + other + "/links").json("$[*].issue.key"))
                .containsExactly("SUP-2");

        // The old key is now a closed stub that points to the issue.
        var stub = admin.get("/api/v1/issues/" + key);
        assertThat((String) stub.json("$.key")).isEqualTo(key);
        assertThat(((Number) stub.json("$.id")).longValue()).isNotEqualTo(id);
        assertThat((String) stub.json("$.summary")).isEqualTo("Printer on fire");
        assertThat((String) stub.json("$.status.category")).isEqualTo("DONE");
        assertThat((String) stub.json("$.resolution.name")).isEqualTo("Moved");
        assertThat((List<String>) admin.get("/api/v1/issues/" + key + "/comments").json("$[*].body"))
                .containsExactly("Moved to SUP-2.");
        var stubLinks = admin.get("/api/v1/issues/" + key + "/links");
        assertThat((List<String>) stubLinks.json("$[*].issue.key")).containsExactly("SUP-2");
        assertThat((List<String>) stubLinks.json("$[*].description")).containsExactly("moved to");

        var history = admin.get("/api/v1/issues/SUP-2/history");
        List<String> fields = history.json("$[-1].items[*].field");
        assertThat(fields).contains("project", "key", "link");
        assertThat((List<String>) history.json("$[-1].items[?(@.field == 'key')].toText")).containsExactly("SUP-2");
    }

    @Test
    void theStubCannotBeTransitionedOrMovedAndMovedIsNotOffered() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "SUP", "Support");
        String key = createIssue(admin, "BUG", "Bug", "Printer on fire");
        String other = createIssue(admin, "BUG", "Bug", "Scanner on fire");
        assertThat(admin.post("/api/v1/issues/" + key + "/move", """
                {"project": "SUP"}""").status()).isEqualTo(200);

        var stub = admin.get("/api/v1/issues/" + key);
        assertThat((String) stub.json("$.movedTo.key")).isEqualTo("SUP-1");
        assertThat(admin.get("/api/v1/issues/SUP-1").json("$.movedTo") == null).isTrue();
        assertThat((List<Object>) admin.get("/api/v1/issues/" + key + "/transitions").json("$")).isEmpty();
        assertThat(code(admin.post("/api/v1/issues/" + key + "/transitions", """
                {"transitionId": %d}""".formatted(transitionId(admin, other, "Close Issue"))))).isEqualTo("issue.moved-stub");
        assertThat(blocker(admin, key)).isEqualTo("MOVED");
        assertThat(code(admin.post("/api/v1/issues/" + key + "/move", """
                {"project": "SUP"}"""))).isEqualTo("issue.move-moved");

        // Moved is listed (for filters), but cannot be chosen when resolving.
        var resolutions = admin.get("/api/v1/resolutions");
        assertThat((List<Boolean>) resolutions.json("$[?(@.name == 'Moved')].selectable")).containsExactly(false);
        assertThat(code(admin.post("/api/v1/issues/" + other + "/transitions", """
                {"transitionId": %d, "resolutionId": %d}""".formatted(transitionId(admin, other, "Close Issue"),
                resolutionId("Moved"))))).isEqualTo("issue.resolution-invalid");
    }

    @Test
    void dropsWhatTheNewProjectDoesNotHave() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "SUP", "Support");
        long uiBug = component(admin, "BUG", "UI");
        long dbBug = component(admin, "BUG", "Database");
        long uiSup = component(admin, "SUP", "ui");
        String epic = createIssue(admin, "BUG", "Epic", "Big one");
        String key = createIssue(admin, "BUG", "Story", "Part", epic);
        assertThat(admin.patch("/api/v1/issues/" + key, """
                {"componentIds": [%d, %d]}""".formatted(uiBug, dbBug)).status()).isEqualTo(200);

        var preview = admin.get("/api/v1/issues/" + key + "/move/preview?project=SUP");
        assertThat(preview.status()).as(preview.toString()).isEqualTo(200);
        assertThat((List<String>) preview.json("$.droppedComponents")).containsExactly("Database");
        assertThat((String) preview.json("$.leavesEpic.key")).isEqualTo(epic);

        var moved = admin.post("/api/v1/issues/" + key + "/move", """
                {"project": "SUP"}""");
        assertThat(moved.status()).as(moved.toString()).isEqualTo(200);
        assertThat((List<Integer>) moved.json("$.components[*].id")).containsExactly((int) uiSup);
        assertThat(moved.json("$.parent") == null).isTrue();
        assertThat((List<String>) admin.get("/api/v1/issues/" + epic).json("$.children[*].key")).isEmpty();
    }

    @Test
    void onlyStandardIssuesWithoutSubtasksMove() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "SUP", "Support");
        String epic = createIssue(admin, "BUG", "Epic", "Epic");
        String parent = createIssue(admin, "BUG", "Story", "Parent");
        String subtask = createIssue(admin, "BUG", "Sub-task", "Child", parent);

        assertThat(blocker(admin, epic)).isEqualTo("EPIC");
        assertThat(blocker(admin, parent)).isEqualTo("HAS_SUBTASKS");
        assertThat(blocker(admin, subtask)).isEqualTo("SUBTASK");
        assertThat(code(admin.post("/api/v1/issues/" + epic + "/move", """
                {"project": "SUP"}"""))).isEqualTo("issue.move-epic");
        assertThat(code(admin.post("/api/v1/issues/" + parent + "/move", """
                {"project": "SUP"}"""))).isEqualTo("issue.move-has-subtasks");
        assertThat(code(admin.post("/api/v1/issues/" + subtask + "/move", """
                {"project": "SUP"}"""))).isEqualTo("issue.move-subtask");
    }

    @Test
    void needsDeleteHereAndCreateThere() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "SUP", "Support");
        createProject(admin, "SECRET", "Secret");
        createProject(admin, "OLD", "Old");
        // SECRET: hidden from regular users; OLD: archived.
        admin.delete("/api/v1/projects/SECRET/roles/" + roleId("Users") + "/groups/" + groupId("users"));
        assertThat(admin.patch("/api/v1/projects/OLD", """
                {"archived": true}""").status()).isEqualTo(200);
        String key = createIssue(admin, "BUG", "Bug", "Something");

        // A regular user may not delete issues in BUG.
        long bobId = createUser(admin, "bob", "bob-password-123");
        ApiClient bob = loginAs("bob", "bob-password-123");
        assertThat(blocker(bob, key)).isEqualTo("PERMISSION");
        assertThat(code(bob.post("/api/v1/issues/" + key + "/move", """
                {"project": "SUP"}"""))).isEqualTo("issue.move-permission");

        // As an administrator of BUG he may; the targets are where he may create issues.
        admin.put("/api/v1/projects/BUG/roles/" + roleId("Administrators") + "/users/" + bobId, null);
        var options = bob.get("/api/v1/issues/" + key + "/move");
        assertThat((Boolean) options.json("$.movable")).isTrue();
        assertThat((List<String>) options.json("$.targets[*].key")).containsExactly("SUP");
        assertThat(code(bob.post("/api/v1/issues/" + key + "/move", """
                {"project": "SECRET"}"""))).isEqualTo("issue.move-target-invalid");
        assertThat(code(bob.post("/api/v1/issues/" + key + "/move", """
                {"project": "OLD"}"""))).isEqualTo("project.archived");
        assertThat(code(bob.post("/api/v1/issues/" + key + "/move", """
                {"project": "BUG"}"""))).isEqualTo("issue.move-same-project");

        // Without the right to create issues anywhere else, there is nowhere to go.
        admin.delete("/api/v1/projects/SUP/roles/" + roleId("Users") + "/groups/" + groupId("users"));
        assertThat(blocker(bob, key)).isEqualTo("NO_TARGETS");
    }

    @Test
    void aStaleVersionIsRefused() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createProject(admin, "SUP", "Support");
        String key = createIssue(admin, "BUG", "Bug", "Something");
        long version = ((Number) admin.get("/api/v1/issues/" + key).json("$.version")).longValue();
        admin.patch("/api/v1/issues/" + key, """
                {"summary": "Something else"}""");

        var response = admin.post("/api/v1/issues/" + key + "/move", """
                {"project": "SUP", "version": %d}""".formatted(version));
        assertThat(response.status()).isEqualTo(409);
        assertThat((String) admin.get("/api/v1/issues/" + key).json("$.project.key")).isEqualTo("BUG");
    }

    private String blocker(ApiClient client, String key) {
        var options = client.get("/api/v1/issues/" + key + "/move");
        assertThat(options.status()).as(options.toString()).isEqualTo(200);
        assertThat((Boolean) options.json("$.movable")).isFalse();
        return options.json("$.blocker");
    }

    private static String code(ApiClient.Response response) {
        assertThat(response.status()).as(response.toString()).isBetween(400, 499);
        return response.json("$.code");
    }

    private long component(ApiClient admin, String project, String name) {
        var response = admin.post("/api/v1/projects/" + project + "/components", """
                {"name": "%s"}""".formatted(name));
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return ((Number) response.json("$.id")).longValue();
    }

    /** A transition the issue offers now, by name. */
    private long transitionId(ApiClient client, String key, String name) {
        List<Number> ids = client.get("/api/v1/issues/" + key + "/transitions").json("$[?(@.name == '" + name + "')].id");
        return ids.getFirst().longValue();
    }

    private long linkTypeId(String name) {
        return jdbc.queryForObject("select id from issue_link_type where name = ?", Long.class, name);
    }
}
