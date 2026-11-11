package org.buggywuggy.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

class GroupAdministrationIntegrationTest extends IntegrationTest {

    @Test
    void groupLifecycle() {
        ApiClient admin = setupAndLoginAdmin();
        long userId = createUser(admin, "lena", "lena-password-12");

        var created = admin.post("/api/v1/admin/groups", """
                {"name": "developers", "description": "All developers"}""");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        long groupId = ((Number) created.json("$.id")).longValue();

        assertThat(admin.put("/api/v1/admin/groups/" + groupId + "/members/" + userId, null).status())
                .isEqualTo(204);
        var members = admin.get("/api/v1/admin/groups/" + groupId + "/members");
        assertThat((List<String>) members.json("$.items[*].username")).containsExactly("lena");
        assertThat((Integer) admin.get("/api/v1/admin/groups/" + groupId).json("$.memberCount")).isEqualTo(1);

        var renamed = admin.patch("/api/v1/admin/groups/" + groupId, """
                {"name": "engineering"}""");
        assertThat((String) renamed.json("$.name")).isEqualTo("engineering");

        var list = admin.get("/api/v1/admin/groups");
        assertThat((List<String>) list.json("$.items[*].name"))
                .containsExactly("administrators", "engineering", "users");

        assertThat(admin.delete("/api/v1/admin/groups/" + groupId).status()).isEqualTo(204);
        assertThat(admin.get("/api/v1/admin/groups/" + groupId).status()).isEqualTo(404);
        assertThat((List<String>) admin.get("/api/v1/admin/users/" + userId).json("$.groups[*].name"))
                .containsExactly("users");
    }

    @Test
    void duplicateGroupNamesAreRejected() {
        ApiClient admin = setupAndLoginAdmin();
        var response = admin.post("/api/v1/admin/groups", """
                {"name": "Users"}""");
        assertThat(response.status()).isEqualTo(409);
        assertThat((String) response.json("$.code")).isEqualTo("group.name-taken");
    }

    @Test
    void builtInGroupsCannotBeRenamedOrDeleted() {
        ApiClient admin = setupAndLoginAdmin();
        long usersGroup = jdbc.queryForObject("select id from user_group where name = 'users'", Long.class);

        assertThat((String) admin.delete("/api/v1/admin/groups/" + usersGroup).json("$.code"))
                .isEqualTo("group.protected");
        assertThat((String) admin.patch("/api/v1/admin/groups/" + usersGroup, """
                {"name": "everyone"}""").json("$.code")).isEqualTo("group.protected");
    }

    @Test
    void joiningAdministratorsGroupTakesEffectAfterNewLogin() {
        ApiClient admin = setupAndLoginAdmin();
        long userId = createUser(admin, "max", "max-password-123");
        long adminsGroup = jdbc.queryForObject("select id from user_group where name = 'administrators'", Long.class);
        ApiClient max = loginAs("max", "max-password-123");
        assertThat(max.get("/api/v1/admin/users").status()).isEqualTo(403);

        admin.put("/api/v1/admin/groups/" + adminsGroup + "/members/" + userId, null);

        // The old session (with outdated authorities) has been ended.
        assertThat(max.get("/api/v1/myself").status()).isEqualTo(401);
        ApiClient maxAgain = loginAs("max", "max-password-123");
        assertThat(maxAgain.get("/api/v1/admin/users").status()).isEqualTo(200);
    }
}
