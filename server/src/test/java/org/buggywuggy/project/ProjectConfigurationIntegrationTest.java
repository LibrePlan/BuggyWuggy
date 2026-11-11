package org.buggywuggy.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

/** Components, versions and role membership of a project. */
class ProjectConfigurationIntegrationTest extends IntegrationTest {

    @Test
    void componentLifecycle() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long leadId = createUser(admin, "sam", "sam-password-123");

        var created = admin.post("/api/v1/projects/BUG/components", """
                {"name": "Backend", "leadId": %d, "defaultAssignee": "COMPONENT_LEAD"}""".formatted(leadId));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        long componentId = ((Number) created.json("$.id")).longValue();
        assertThat((String) created.json("$.lead.username")).isEqualTo("sam");

        assertThat((String) admin.post("/api/v1/projects/BUG/components", """
                {"name": "backend"}""").json("$.code")).isEqualTo("component.name-taken");

        // A component lead is needed while issues are assigned to the component lead.
        assertThat((String) admin.patch("/api/v1/projects/BUG/components/" + componentId, """
                {"clearLead": true}""").json("$.code")).isEqualTo("component.lead-required");
        var cleared = admin.patch("/api/v1/projects/BUG/components/" + componentId, """
                {"clearLead": true, "defaultAssignee": "PROJECT_DEFAULT"}""");
        assertThat(cleared.json("$.lead") == null).isTrue();

        admin.post("/api/v1/projects/BUG/components", """
                {"name": "API"}""");
        assertThat((List<String>) admin.get("/api/v1/projects/BUG/components").json("$[*].name"))
                .containsExactly("API", "Backend");

        assertThat(admin.delete("/api/v1/projects/BUG/components/" + componentId).status()).isEqualTo(204);
        assertThat((List<String>) admin.get("/api/v1/projects/BUG/components").json("$[*].name"))
                .containsExactly("API");
    }

    @Test
    void usersCanSeeButNotChangeConfiguration() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        admin.post("/api/v1/projects/BUG/components", """
                {"name": "Backend"}""");
        createUser(admin, "tess", "tess-password-12");
        ApiClient tess = loginAs("tess", "tess-password-12");

        assertThat((List<String>) tess.get("/api/v1/projects/BUG/components").json("$[*].name"))
                .containsExactly("Backend");
        assertThat(tess.post("/api/v1/projects/BUG/components", """
                {"name": "Frontend"}""").status()).isEqualTo(403);
        assertThat(tess.post("/api/v1/projects/BUG/versions", """
                {"name": "1.0"}""").status()).isEqualTo(403);
        assertThat(tess.put("/api/v1/projects/BUG/roles/" + roleId("Developers") + "/groups/" + groupId("users"),
                null).status()).isEqualTo(403);
    }

    @Test
    void versionLifecycleAndOrdering() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long v1 = createVersion(admin, "1.0");
        long v2 = createVersion(admin, "2.0");
        long v3 = createVersion(admin, "1.1");

        assertThat((List<String>) admin.get("/api/v1/projects/BUG/versions").json("$[*].name"))
                .containsExactly("1.0", "2.0", "1.1");

        var reordered = admin.put("/api/v1/projects/BUG/versions/order", """
                {"versionIds": [%d, %d, %d]}""".formatted(v1, v3, v2));
        assertThat((List<String>) reordered.json("$[*].name")).containsExactly("1.0", "1.1", "2.0");
        assertThat((List<String>) admin.get("/api/v1/projects/BUG/versions").json("$[*].name"))
                .containsExactly("1.0", "1.1", "2.0");

        assertThat((String) admin.put("/api/v1/projects/BUG/versions/order", """
                {"versionIds": [%d, %d]}""".formatted(v1, v2)).json("$.code")).isEqualTo("version.order-mismatch");

        var released = admin.patch("/api/v1/projects/BUG/versions/" + v1, """
                {"released": true, "startDate": "2026-01-01", "releaseDate": "2026-03-01"}""");
        assertThat((Boolean) released.json("$.released")).isTrue();
        assertThat((String) released.json("$.releaseDate")).isEqualTo("2026-03-01");

        assertThat((String) admin.patch("/api/v1/projects/BUG/versions/" + v1, """
                {"releaseDate": "2025-12-31"}""").json("$.code")).isEqualTo("version.dates-invalid");

        var cleared = admin.patch("/api/v1/projects/BUG/versions/" + v1, """
                {"clearReleaseDate": true}""");
        assertThat(cleared.json("$.releaseDate") == null).isTrue();

        assertThat((String) admin.post("/api/v1/projects/BUG/versions", """
                {"name": "1.0"}""").json("$.code")).isEqualTo("version.name-taken");

        assertThat(admin.delete("/api/v1/projects/BUG/versions/" + v2).status()).isEqualTo(204);
        assertThat((List<String>) admin.get("/api/v1/projects/BUG/versions").json("$[*].name"))
                .containsExactly("1.0", "1.1");
    }

    @Test
    void componentsAndVersionsOfOtherProjectsAreNotReachable() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "ONE", "Project one");
        createProject(admin, "TWO", "Project two");
        long componentOfOne = ((Number) admin.post("/api/v1/projects/ONE/components", """
                {"name": "Backend"}""").json("$.id")).longValue();
        long versionOfOne = ((Number) admin.post("/api/v1/projects/ONE/versions", """
                {"name": "1.0"}""").json("$.id")).longValue();

        assertThat(admin.delete("/api/v1/projects/TWO/components/" + componentOfOne).status()).isEqualTo(404);
        assertThat(admin.patch("/api/v1/projects/TWO/versions/" + versionOfOne, """
                {"name": "hijacked"}""").status()).isEqualTo(404);
    }

    @Test
    void roleMembershipForUsersAndGroups() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long userId = createUser(admin, "uma", "uma-password-123");
        long developers = roleId("Developers");

        admin.put("/api/v1/projects/BUG/roles/" + developers + "/users/" + userId, null);
        admin.put("/api/v1/projects/BUG/roles/" + developers + "/users/" + userId, null); // idempotent
        admin.put("/api/v1/projects/BUG/roles/" + developers + "/groups/" + groupId("users"), null);

        var roles = admin.get("/api/v1/projects/BUG/roles");
        assertThat((List<String>) roles.json("$[?(@.name == 'Developers')].users[*].username"))
                .containsExactly("uma");
        assertThat((List<String>) roles.json("$[?(@.name == 'Developers')].groups[*].name"))
                .containsExactly("users");

        admin.delete("/api/v1/projects/BUG/roles/" + developers + "/users/" + userId);
        assertThat((List<Object>) admin.get("/api/v1/projects/BUG/roles")
                .json("$[?(@.name == 'Developers')].users[*]")).isEmpty();

        assertThat((List<String>) admin.get("/api/v1/project-roles").json("$[*].name"))
                .containsExactly("Administrators", "Developers", "Users");
    }

    private long createVersion(ApiClient client, String name) {
        var response = client.post("/api/v1/projects/BUG/versions", """
                {"name": "%s"}""".formatted(name));
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return ((Number) response.json("$.id")).longValue();
    }
}
