package org.buggywuggy.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

class UserAdministrationIntegrationTest extends IntegrationTest {

    @Test
    void adminCreatesUserWhoCanLogIn() {
        ApiClient admin = setupAndLoginAdmin();
        long id = createUser(admin, "alice", "alice-password-1");

        var user = admin.get("/api/v1/admin/users/" + id);
        assertThat((String) user.json("$.username")).isEqualTo("alice");
        assertThat((List<String>) user.json("$.groups[*].name")).containsExactly("users");

        ApiClient alice = loginAs("alice", "alice-password-1");
        var me = alice.get("/api/v1/myself");
        assertThat((Boolean) me.json("$.systemAdmin")).isFalse();
        assertThat((List<String>) me.json("$.globalPermissions")).containsExactly("BROWSE_USERS");
    }

    @Test
    void regularUserCannotUseAdminApi() {
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "bob", "bob-password-12");
        ApiClient bob = loginAs("bob", "bob-password-12");

        var response = bob.get("/api/v1/admin/users");
        assertThat(response.status()).isEqualTo(403);
        assertThat((String) response.json("$.code")).isEqualTo("error.forbidden");
    }

    @Test
    void usernamesAndEmailsAreUniqueIgnoringCase() {
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "carol", "carol-password-1");

        var sameName = admin.post("/api/v1/admin/users", """
                {"username": "CAROL", "displayName": "C", "email": "other@example.org", "password": "carol-password-1"}""");
        assertThat(sameName.status()).isEqualTo(409);
        assertThat((String) sameName.json("$.code")).isEqualTo("user.username-taken");

        var sameEmail = admin.post("/api/v1/admin/users", """
                {"username": "carol2", "displayName": "C", "email": "Carol@Example.org", "password": "carol-password-1"}""");
        assertThat(sameEmail.status()).isEqualTo(409);
        assertThat((String) sameEmail.json("$.code")).isEqualTo("user.email-taken");
    }

    @Test
    void searchFindsUsersByNameOrEmail() {
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "dave", "dave-password-12");
        createUser(admin, "erin", "erin-password-12");

        var all = admin.get("/api/v1/admin/users");
        assertThat((Integer) all.json("$.total")).isEqualTo(3);

        var found = admin.get("/api/v1/admin/users?query=DAV");
        assertThat((List<String>) found.json("$.items[*].username")).containsExactly("dave");

        var wildcard = admin.get("/api/v1/admin/users?query=%25");
        assertThat((Integer) wildcard.json("$.total")).isZero();
    }

    @Test
    void deactivationEndsExistingSessions() {
        ApiClient admin = setupAndLoginAdmin();
        long id = createUser(admin, "frank", "frank-password-1");
        ApiClient frank = loginAs("frank", "frank-password-1");
        assertThat(frank.get("/api/v1/myself").status()).isEqualTo(200);

        assertThat(admin.patch("/api/v1/admin/users/" + id, """
                {"active": false}""").status()).isEqualTo(200);

        assertThat(frank.get("/api/v1/myself").status()).isEqualTo(401);
    }

    @Test
    void adminCannotDeactivateOrDemoteThemselves() {
        ApiClient admin = setupAndLoginAdmin();
        long adminId = ((Number) admin.get("/api/v1/myself").json("$.id")).longValue();

        var deactivate = admin.patch("/api/v1/admin/users/" + adminId, """
                {"active": false}""");
        assertThat((String) deactivate.json("$.code")).isEqualTo("user.cannot-deactivate-self");

        var demote = admin.patch("/api/v1/admin/users/" + adminId, """
                {"systemAdmin": false}""");
        assertThat((String) demote.json("$.code")).isEqualTo("user.cannot-revoke-own-admin");
    }

    @Test
    void passwordResetByAdminEndsSessionsAndSetsNewPassword() {
        ApiClient admin = setupAndLoginAdmin();
        long id = createUser(admin, "gina", "gina-password-12");
        ApiClient gina = loginAs("gina", "gina-password-12");

        assertThat(admin.put("/api/v1/admin/users/" + id + "/password", """
                {"password": "new-gina-password"}""").status()).isEqualTo(204);

        assertThat(gina.get("/api/v1/myself").status()).isEqualTo(401);
        assertThat(client().login("gina", "gina-password-12").status()).isEqualTo(401);
        assertThat(client().login("gina", "new-gina-password").status()).isEqualTo(200);
    }

    @Test
    void ownPasswordChangeKeepsCurrentSessionAndEndsOthers() {
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "hank", "hank-password-12");
        ApiClient laptop = loginAs("hank", "hank-password-12");
        ApiClient phone = loginAs("hank", "hank-password-12");

        var wrong = laptop.put("/api/v1/myself/password", """
                {"currentPassword": "wrong", "newPassword": "hank-new-password"}""");
        assertThat((String) wrong.json("$.code")).isEqualTo("auth.wrong-current-password");

        assertThat(laptop.put("/api/v1/myself/password", """
                {"currentPassword": "hank-password-12", "newPassword": "hank-new-password"}""").status())
                .isEqualTo(204);

        assertThat(laptop.get("/api/v1/myself").status()).isEqualTo(200);
        assertThat(phone.get("/api/v1/myself").status()).isEqualTo(401);
    }

    @Test
    void userUpdatesOwnProfile() {
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "ivy", "ivy-password-123");
        ApiClient ivy = loginAs("ivy", "ivy-password-123");

        var updated = ivy.patch("/api/v1/myself", """
                {"displayName": "Ivy Example", "locale": "nl-NL", "timeZone": "Europe/Amsterdam"}""");
        assertThat(updated.status()).as(updated.toString()).isEqualTo(200);
        assertThat((String) updated.json("$.displayName")).isEqualTo("Ivy Example");
        assertThat((String) updated.json("$.timeZone")).isEqualTo("Europe/Amsterdam");

        var badZone = ivy.patch("/api/v1/myself", """
                {"timeZone": "Mars/Olympus_Mons"}""");
        assertThat((String) badZone.json("$.code")).isEqualTo("validation.time-zone.invalid");
    }

    @Test
    void onlySystemAdminsManageSystemAdmins() {
        ApiClient root = setupAndLoginAdmin();
        long rootId = ((Number) root.get("/api/v1/myself").json("$.id")).longValue();
        long jackId = createUser(root, "jack", "jack-password-12");
        long adminsGroup = jdbc.queryForObject("select id from user_group where name = 'administrators'", Long.class);
        root.put("/api/v1/admin/groups/" + adminsGroup + "/members/" + jackId, null);

        // jack administers via the group, but is not a system administrator
        ApiClient jack = loginAs("jack", "jack-password-12");
        assertThat(jack.get("/api/v1/admin/users").status()).isEqualTo(200);

        var grant = jack.post("/api/v1/admin/users", """
                {"username": "kim", "displayName": "Kim", "email": "kim@example.org",
                 "password": "kim-password-123", "systemAdmin": true}""");
        assertThat((String) grant.json("$.code")).isEqualTo("user.system-admin-required");

        var resetRoot = jack.put("/api/v1/admin/users/" + rootId + "/password", """
                {"password": "taken-over-password"}""");
        assertThat(resetRoot.status()).isEqualTo(403);
    }
}
