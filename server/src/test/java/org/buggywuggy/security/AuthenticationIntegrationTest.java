package org.buggywuggy.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

class AuthenticationIntegrationTest extends IntegrationTest {

    @Test
    void loginReturnsCurrentUserAndStartsSession() {
        ApiClient admin = setupAndLoginAdmin();

        var me = admin.get("/api/v1/myself");
        assertThat(me.status()).isEqualTo(200);
        assertThat((String) me.json("$.username")).isEqualTo(ADMIN);
        assertThat((Boolean) me.json("$.systemAdmin")).isTrue();
        assertThat((List<String>) me.json("$.globalPermissions"))
                .containsExactly("ADMINISTER", "BROWSE_USERS", "CREATE_PROJECT");
        assertThat(admin.cookie("BUGGYWUGGY_SESSION")).isPresent();
    }

    @Test
    void usernameIsCaseInsensitive() {
        setupAndLoginAdmin();
        assertThat(client().login("ADMIN", ADMIN_PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void logoutEndsSession() {
        ApiClient admin = setupAndLoginAdmin();
        assertThat(admin.logout().status()).isEqualTo(204);
        assertThat(admin.get("/api/v1/myself").status()).isEqualTo(401);
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() {
        setupAndLoginAdmin();
        var wrongPassword = client().login(ADMIN, "not-the-password");
        var unknownUser = client().login("nobody", "not-the-password");

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(unknownUser.status()).isEqualTo(401);
        assertThat((String) wrongPassword.json("$.code")).isEqualTo("auth.bad-credentials");
        assertThat((String) unknownUser.json("$.detail")).isEqualTo(wrongPassword.json("$.detail"));
    }

    @Test
    void repeatedFailuresAreThrottled() {
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "throttled", "throttled-password");

        ApiClient attacker = client();
        for (int i = 0; i < 5; i++) {
            assertThat(attacker.login("throttled", "guess-" + i).status()).isEqualTo(401);
        }
        // Even the correct password is refused while throttled.
        var blocked = attacker.login("throttled", "throttled-password");
        assertThat(blocked.status()).isEqualTo(429);
        assertThat((String) blocked.json("$.code")).isEqualTo("auth.throttled");
    }

    @Test
    void requestsWithoutCsrfTokenAreRejected() {
        setupAndLoginAdmin();
        var response = client().withoutCsrf().login(ADMIN, ADMIN_PASSWORD);
        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void apiRequiresAuthentication() {
        var response = client().get("/api/v1/myself");
        assertThat(response.status()).isEqualTo(401);
        assertThat((String) response.json("$.code")).isEqualTo("error.unauthenticated");
        assertThat(client().get("/api/v1/admin/users").status()).isEqualTo(401);
    }

    @Test
    void deactivatedUserCannotLogIn() {
        ApiClient admin = setupAndLoginAdmin();
        long id = createUser(admin, "leaver", "leaver-password-1");
        admin.patch("/api/v1/admin/users/" + id, """
                {"active": false}""");

        var response = client().login("leaver", "leaver-password-1");
        assertThat(response.status()).isEqualTo(401);
        assertThat((String) response.json("$.code")).isEqualTo("auth.bad-credentials");
    }

    @Test
    void openApiDocumentIsPublic() {
        var response = client().get("/api/openapi");
        assertThat(response.status()).isEqualTo(200);
        assertThat((String) response.json("$.info.title")).isEqualTo("BuggyWuggy API");
    }
}
