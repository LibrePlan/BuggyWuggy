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
        assertThat((Boolean) admin.get("/api/v1/admin/groups/" + groupId).json("$.builtIn")).isFalse();

        var renamed = admin.patch("/api/v1/admin/groups/" + groupId, """
                {"name": "engineering"}""");
        assertThat((String) renamed.json("$.name")).isEqualTo("engineering");

        var list = admin.get("/api/v1/admin/groups");
        assertThat((List<String>) list.json("$.items[*].name"))
                .containsExactly("administrators", "engineering", "users");
        assertThat((List<Boolean>) list.json("$.items[*].builtIn")).containsExactly(true, false, true);

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

    @Test
    void groupsCanBeSearchedAndPicked() {
        ApiClient admin = setupAndLoginAdmin();
        admin.post("/api/v1/admin/groups", """
                {"name": "QA team"}""");
        assertThat((List<String>) admin.get("/api/v1/admin/groups?query=qa").json("$.items[*].name"))
                .containsExactly("QA team");
        assertThat((List<String>) admin.get("/api/v1/admin/groups?query=_").json("$.items[*].name")).isEmpty();

        createUser(admin, "nina", "nina-password-12");
        ApiClient nina = loginAs("nina", "nina-password-12");
        assertThat((List<String>) nina.get("/api/v1/groups/picker?query=ADM").json("$[*].name"))
                .containsExactly("administrators");
        assertThat((List<String>) nina.get("/api/v1/groups/picker").json("$[*].name"))
                .containsExactly("administrators", "QA team", "users");
    }

    @Test
    void globalPermissionsCanBeGrantedAndRevoked() {
        ApiClient admin = setupAndLoginAdmin();
        long userId = createUser(admin, "olaf", "olaf-password-12");
        long groupId = ((Number) admin.post("/api/v1/admin/groups", """
                {"name": "project-creators"}""").json("$.id")).longValue();
        admin.put("/api/v1/admin/groups/" + groupId + "/members/" + userId, null);

        var list = admin.get("/api/v1/admin/global-permissions");
        assertThat((List<String>) list.json("$[*].permission"))
                .containsExactly("ADMINISTER", "CREATE_PROJECT", "BROWSE_USERS");
        assertThat((List<String>) list.json("$[1].groups[*].name")).containsExactly("administrators");

        ApiClient olaf = loginAs("olaf", "olaf-password-12");
        assertThat((List<String>) olaf.get("/api/v1/myself").json("$.globalPermissions"))
                .doesNotContain("CREATE_PROJECT");

        assertThat(admin.put("/api/v1/admin/global-permissions/CREATE_PROJECT/groups/" + groupId, null).status())
                .isEqualTo(204);
        assertThat((List<String>) admin.get("/api/v1/admin/global-permissions").json("$[1].groups[*].name"))
                .containsExactly("administrators", "project-creators");
        // Members must log in again to get the new permission.
        assertThat(olaf.get("/api/v1/myself").status()).isEqualTo(401);
        olaf = loginAs("olaf", "olaf-password-12");
        assertThat((List<String>) olaf.get("/api/v1/myself").json("$.globalPermissions")).contains("CREATE_PROJECT");
        assertThat(olaf.get("/api/v1/admin/global-permissions").status()).isEqualTo(403);

        assertThat(admin.delete("/api/v1/admin/global-permissions/CREATE_PROJECT/groups/" + groupId).status())
                .isEqualTo(204);
        assertThat(olaf.get("/api/v1/myself").status()).isEqualTo(401);

        assertThat((String) admin.put("/api/v1/admin/global-permissions/FLY/groups/" + groupId, null).json("$.code"))
                .isEqualTo("global-permission.not-found");
        assertThat(admin.put("/api/v1/admin/global-permissions/BROWSE_USERS/groups/999999", null).status())
                .isEqualTo(404);
    }

    @Test
    void administratorsCannotRevokeTheirOwnAdministration() {
        ApiClient admin = setupAndLoginAdmin();
        long userId = createUser(admin, "pia", "pia-password-123");
        long adminsGroup = jdbc.queryForObject("select id from user_group where name = 'administrators'", Long.class);
        admin.put("/api/v1/admin/groups/" + adminsGroup + "/members/" + userId, null);
        ApiClient pia = loginAs("pia", "pia-password-123");

        var refused = pia.delete("/api/v1/admin/global-permissions/ADMINISTER/groups/" + adminsGroup);
        assertThat(refused.status()).isEqualTo(403);
        assertThat((String) refused.json("$.code")).isEqualTo("global-permission.cannot-revoke-own");

        // A system administrator can.
        assertThat(admin.delete("/api/v1/admin/global-permissions/ADMINISTER/groups/" + adminsGroup).status())
                .isEqualTo(204);
    }
}
