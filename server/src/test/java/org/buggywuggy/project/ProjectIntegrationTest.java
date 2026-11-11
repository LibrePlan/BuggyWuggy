package org.buggywuggy.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

class ProjectIntegrationTest extends IntegrationTest {

    @Test
    void createsProjectWithDefaultRoleMembers() {
        ApiClient admin = setupAndLoginAdmin();
        var created = admin.post("/api/v1/projects", """
                {"key": "bug", "name": "Bug Tracker", "description": "Tracks bugs"}""");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat((String) created.json("$.key")).isEqualTo("BUG");
        assertThat((String) created.json("$.lead.username")).isEqualTo(ADMIN);

        var roles = admin.get("/api/v1/projects/BUG/roles");
        assertThat((List<String>) roles.json("$[?(@.name == 'Administrators')].users[*].username"))
                .containsExactly(ADMIN);
        assertThat((List<String>) roles.json("$[?(@.name == 'Administrators')].groups[*].name"))
                .containsExactly("administrators");
        assertThat((List<String>) roles.json("$[?(@.name == 'Users')].groups[*].name")).containsExactly("users");
        assertThat((List<Object>) roles.json("$[?(@.name == 'Developers')].users[*]")).isEmpty();
    }

    @Test
    void regularUserCannotCreateProjects() {
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "nora", "nora-password-12");
        var response = loginAs("nora", "nora-password-12").post("/api/v1/projects", """
                {"key": "NORA", "name": "Nora's project"}""");
        assertThat(response.status()).isEqualTo(403);
        assertThat((String) response.json("$.code")).isEqualTo("project.create-permission-required");
    }

    @Test
    void usersRoleGrantsBrowsingAndReporting() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        createUser(admin, "otto", "otto-password-12");
        ApiClient otto = loginAs("otto", "otto-password-12");

        assertThat((List<String>) otto.get("/api/v1/projects").json("$.items[*].key")).containsExactly("BUG");
        assertThat(otto.get("/api/v1/projects/BUG").status()).isEqualTo(200);
        List<String> permissions = otto.get("/api/v1/projects/BUG/my-permissions").json("$.permissions");
        assertThat(permissions).contains("BROWSE_PROJECT", "CREATE_ISSUE", "ADD_COMMENT")
                .doesNotContain("EDIT_ISSUE", "ADMINISTER_PROJECT");
    }

    @Test
    void projectsWithoutBrowsePermissionAreInvisible() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "SECRET", "Secret project");
        admin.delete("/api/v1/projects/SECRET/roles/" + roleId("Users") + "/groups/" + groupId("users"));
        createUser(admin, "pia", "pia-password-123");
        ApiClient pia = loginAs("pia", "pia-password-123");

        assertThat((Integer) pia.get("/api/v1/projects").json("$.total")).isZero();
        var response = pia.get("/api/v1/projects/SECRET");
        assertThat(response.status()).isEqualTo(404);
        assertThat((String) response.json("$.code")).isEqualTo("project.not-found");
    }

    @Test
    void roleChangesTakeEffectWithoutNewLogin() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long userId = createUser(admin, "quinn", "quinn-password-1");
        ApiClient quinn = loginAs("quinn", "quinn-password-1");
        assertThat((List<String>) quinn.get("/api/v1/projects/BUG/my-permissions").json("$.permissions"))
                .doesNotContain("EDIT_ISSUE");

        assertThat(admin.put("/api/v1/projects/BUG/roles/" + roleId("Developers") + "/users/" + userId, null)
                .status()).isEqualTo(204);

        assertThat((List<String>) quinn.get("/api/v1/projects/BUG/my-permissions").json("$.permissions"))
                .contains("EDIT_ISSUE", "TRANSITION_ISSUE", "ASSIGNABLE_USER");
    }

    @Test
    void validatesKeysAndRejectsDuplicates() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");

        assertThat(admin.post("/api/v1/projects", """
                {"key": "1AB", "name": "Invalid"}""").status()).isEqualTo(400);
        assertThat((String) admin.post("/api/v1/projects", """
                {"key": "bug", "name": "Other"}""").json("$.code")).isEqualTo("project.key-taken");
        assertThat((String) admin.post("/api/v1/projects", """
                {"key": "OTHER", "name": "BUG TRACKER"}""").json("$.code")).isEqualTo("project.name-taken");
    }

    @Test
    void formerKeysKeepResolvingAndStayReserved() {
        ApiClient admin = setupAndLoginAdmin();
        long id = createProject(admin, "BUG", "Bug Tracker");

        var renamed = admin.patch("/api/v1/projects/BUG", """
                {"key": "BT"}""");
        assertThat((String) renamed.json("$.key")).isEqualTo("BT");

        // Old key and numeric id both resolve to the project.
        assertThat((String) admin.get("/api/v1/projects/BUG").json("$.key")).isEqualTo("BT");
        assertThat((String) admin.get("/api/v1/projects/" + id).json("$.key")).isEqualTo("BT");

        // Another project cannot take the former key.
        assertThat((String) admin.post("/api/v1/projects", """
                {"key": "BUG", "name": "Imposter"}""").json("$.code")).isEqualTo("project.key-taken");

        // The project itself can switch back.
        assertThat((String) admin.patch("/api/v1/projects/BT", """
                {"key": "BUG"}""").json("$.key")).isEqualTo("BUG");
        assertThat((String) admin.get("/api/v1/projects/BT").json("$.key")).isEqualTo("BUG");
    }

    @Test
    void projectAdministratorsCannotChangeKeyArchiveOrDelete() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long userId = createUser(admin, "rosa", "rosa-password-12");
        admin.put("/api/v1/projects/BUG/roles/" + roleId("Administrators") + "/users/" + userId, null);
        ApiClient rosa = loginAs("rosa", "rosa-password-12");

        assertThat(rosa.patch("/api/v1/projects/BUG", """
                {"name": "Renamed by Rosa"}""").status()).isEqualTo(200);
        for (var response : List.of(
                rosa.patch("/api/v1/projects/BUG", """
                        {"key": "ROSA"}"""),
                rosa.patch("/api/v1/projects/BUG", """
                        {"archived": true}"""),
                rosa.delete("/api/v1/projects/BUG"))) {
            assertThat((String) response.json("$.code")).isEqualTo("project.global-admin-required");
        }
    }

    @Test
    void archivedProjectsAreReadOnlyAndHiddenByDefault() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "OLD", "Old project");

        assertThat((Boolean) admin.patch("/api/v1/projects/OLD", """
                {"archived": true}""").json("$.archived")).isTrue();
        assertThat((Integer) admin.get("/api/v1/projects").json("$.total")).isZero();
        assertThat((Integer) admin.get("/api/v1/projects?includeArchived=true").json("$.total")).isEqualTo(1);

        assertThat((String) admin.patch("/api/v1/projects/OLD", """
                {"name": "Changed"}""").json("$.code")).isEqualTo("project.archived");
        assertThat((String) admin.post("/api/v1/projects/OLD/components", """
                {"name": "Backend"}""").json("$.code")).isEqualTo("project.archived");

        assertThat(admin.patch("/api/v1/projects/OLD", """
                {"archived": false}""").status()).isEqualTo(200);
        assertThat(admin.post("/api/v1/projects/OLD/components", """
                {"name": "Backend"}""").status()).isEqualTo(201);
    }

    @Test
    void globalAdministratorDeletesProject() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "GONE", "Short-lived");
        assertThat(admin.delete("/api/v1/projects/GONE").status()).isEqualTo(204);
        assertThat(admin.get("/api/v1/projects/GONE").status()).isEqualTo(404);
    }

    @Test
    void searchMatchesNameOrKey() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "WEB", "Website");
        createProject(admin, "APP", "Mobile app");

        assertThat((List<String>) admin.get("/api/v1/projects?query=web").json("$.items[*].key"))
                .containsExactly("WEB");
        assertThat((List<String>) admin.get("/api/v1/projects?query=mobile").json("$.items[*].key"))
                .containsExactly("APP");
        assertThat((List<String>) admin.get("/api/v1/projects").json("$.items[*].key"))
                .containsExactly("APP", "WEB");
    }

    @Test
    void listsAssignableUsersFilteredByQuery() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "BUG", "Bug Tracker");
        long devId = createUser(admin, "devon", "devon-password-1");
        createUser(admin, "otto", "otto-password-12");
        admin.put("/api/v1/projects/BUG/roles/" + roleId("Developers") + "/users/" + devId, null);

        // Developers and Administrators are assignable by default; plain users are not.
        ApiClient otto = loginAs("otto", "otto-password-12");
        assertThat((List<String>) otto.get("/api/v1/projects/BUG/assignable-users").json("$[*].username"))
                .containsExactlyInAnyOrder(ADMIN, "devon");
        assertThat((List<String>) otto.get("/api/v1/projects/BUG/assignable-users?query=DEV").json("$[*].username"))
                .containsExactly("devon");
        assertThat((List<String>) admin.get("/api/v1/projects/BUG/assignable-users?limit=1").json("$[*].id"))
                .hasSize(1);

        admin.patch("/api/v1/admin/users/" + devId, """
                {"active": false}""");
        assertThat((List<String>) otto.get("/api/v1/projects/BUG/assignable-users").json("$[*].username"))
                .containsExactly(ADMIN);
    }
}
