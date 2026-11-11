package org.buggywuggy.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.junit.jupiter.api.Test;

class IssueWorkflowIntegrationTest extends IssueTestSupport {

    @Test
    void listsTransitionsAvailableFromCurrentStatus() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Bug", "Workflow");

        var transitions = admin.get("/api/v1/issues/" + key + "/transitions");
        assertThat((List<String>) transitions.json("$[*].name"))
                .containsExactly("Start Progress", "Resolve Issue", "Close Issue");
        assertThat((List<Boolean>) transitions.json("$[*].setsResolution")).containsExactly(false, true, true);
    }

    @Test
    void fullLifecycleWithResolutionHandling() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Bug", "Lifecycle");

        var inProgress = transition(admin, key, "Start Progress", null);
        assertThat((String) inProgress.json("$.status.name")).isEqualTo("In Progress");
        assertThat(inProgress.json("$.resolution") == null).isTrue();

        var resolved = transition(admin, key, "Resolve Issue", resolutionId("Fixed"));
        assertThat((String) resolved.json("$.status.name")).isEqualTo("Resolved");
        assertThat((String) resolved.json("$.resolution.name")).isEqualTo("Fixed");
        String resolvedAt = resolved.json("$.resolvedAt");
        assertThat(resolvedAt).isNotNull();

        // Done -> done keeps the resolution and the original resolution date.
        var closed = transition(admin, key, "Close Issue", null);
        assertThat((String) closed.json("$.resolution.name")).isEqualTo("Fixed");
        assertThat((String) closed.json("$.resolvedAt")).isEqualTo(resolvedAt);

        var reopened = transition(admin, key, "Reopen Issue", null);
        assertThat((String) reopened.json("$.status.name")).isEqualTo("Reopened");
        assertThat(reopened.json("$.resolution") == null).isTrue();
        assertThat(reopened.json("$.resolvedAt") == null).isTrue();

        List<String> fields = admin.get("/api/v1/issues/" + key + "/history").json("$[*].items[*].field");
        assertThat(fields).containsExactly(
                "status",                   // Open -> In Progress
                "status", "resolution",     // -> Resolved, Fixed
                "status",                   // -> Closed (resolution unchanged)
                "status", "resolution");    // -> Reopened, resolution cleared
    }

    @Test
    void usesDefaultResolutionWhenNoneGiven() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Bug", "Quick close");

        assertThat((String) transition(admin, key, "Close Issue", null).json("$.resolution.name")).isEqualTo("Done");
    }

    @Test
    void rejectsUnavailableTransitionsAndMisplacedResolutions() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Bug", "Invalid moves");
        long reopenFromResolved = transitionIdByName("Reopen Issue");

        assertThat((String) admin.post("/api/v1/issues/" + key + "/transitions", """
                {"transitionId": %d}""".formatted(reopenFromResolved)).json("$.code"))
                .isEqualTo("issue.transition-invalid");

        long startProgress = availableTransitionId(admin, key, "Start Progress");
        assertThat((String) admin.post("/api/v1/issues/" + key + "/transitions", """
                {"transitionId": %d, "resolutionId": %d}""".formatted(startProgress, resolutionId("Fixed")))
                .json("$.code")).isEqualTo("issue.resolution-not-allowed");
    }

    @Test
    void transitionsRequirePermission() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createUser(admin, "beth", "beth-password-12");
        ApiClient beth = loginAs("beth", "beth-password-12");
        String key = createIssue(beth, "BUG", "Bug", "Reported by Beth");

        assertThat((List<Object>) beth.get("/api/v1/issues/" + key + "/transitions").json("$")).isEmpty();
        long startProgress = availableTransitionId(admin, key, "Start Progress");
        assertThat(beth.post("/api/v1/issues/" + key + "/transitions", """
                {"transitionId": %d}""".formatted(startProgress)).status()).isEqualTo(403);
    }

    @Test
    void staleVersionIsRejected() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        String key = createIssue(admin, "BUG", "Bug", "Versioned");
        int version = admin.get("/api/v1/issues/" + key).json("$.version");
        admin.patch("/api/v1/issues/" + key, """
                {"summary": "Changed meanwhile"}""");

        long startProgress = availableTransitionId(admin, key, "Start Progress");
        assertThat(admin.post("/api/v1/issues/" + key + "/transitions", """
                {"transitionId": %d, "version": %d}""".formatted(startProgress, version)).status()).isEqualTo(409);
    }

    // --- helpers -----------------------------------------------------------------------

    private ApiClient.Response transition(ApiClient client, String key, String name, Long resolution) {
        long id = availableTransitionId(client, key, name);
        var response = client.post("/api/v1/issues/" + key + "/transitions", """
                {"transitionId": %d%s}""".formatted(id, resolution == null ? "" : ", \"resolutionId\": " + resolution));
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return response;
    }

    private long availableTransitionId(ApiClient client, String key, String name) {
        List<Number> ids = client.get("/api/v1/issues/" + key + "/transitions").json("$[?(@.name == '" + name + "')].id");
        assertThat(ids).as("transition " + name).hasSize(1);
        return ids.getFirst().longValue();
    }

    /** Any transition with this name (used to pick one that is not available from the current status). */
    private long transitionIdByName(String name) {
        return jdbc.queryForObject("select min(id) from workflow_transition where name = ?", Long.class, name);
    }
}
