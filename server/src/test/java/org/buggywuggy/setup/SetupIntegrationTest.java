package org.buggywuggy.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

class SetupIntegrationTest extends IntegrationTest {

    private static final String SETUP_BODY = """
            {"setupToken": "%s", "username": "root", "displayName": "Root",
             "email": "root@example.org", "password": "a-long-enough-password"}""";

    @Test
    void freshInstallationRequiresSetup() {
        var status = client().get("/api/v1/setup");
        assertThat(status.status()).isEqualTo(200);
        assertThat((Boolean) status.json("$.setupRequired")).isTrue();
    }

    @Test
    void rejectsWrongSetupToken() {
        var response = client().post("/api/v1/setup/admin", SETUP_BODY.formatted("wrong-token"));
        assertThat(response.status()).isEqualTo(403);
        assertThat((String) response.json("$.code")).isEqualTo("setup.invalid-token");
        assertThat((Boolean) client().get("/api/v1/setup").json("$.setupRequired")).isTrue();
    }

    @Test
    void createsSystemAdministratorOnceAndThenCloses() {
        var created = client().post("/api/v1/setup/admin", SETUP_BODY.formatted(SETUP_TOKEN));
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat((Boolean) created.json("$.systemAdmin")).isTrue();
        assertThat((List<String>) created.json("$.groups[*].name")).containsExactly("administrators", "users");

        assertThat((Boolean) client().get("/api/v1/setup").json("$.setupRequired")).isFalse();

        var again = client().post("/api/v1/setup/admin", SETUP_BODY.formatted(SETUP_TOKEN));
        assertThat(again.status()).isEqualTo(409);
        assertThat((String) again.json("$.code")).isEqualTo("setup.already-completed");
    }

    @Test
    void validatesInput() {
        var response = client().post("/api/v1/setup/admin", """
                {"setupToken": "%s", "username": "x", "displayName": "X",
                 "email": "not-an-email", "password": "short"}""".formatted(SETUP_TOKEN));
        assertThat(response.status()).isEqualTo(400);
        assertThat((List<String>) response.json("$.errors[*].field"))
                .containsExactlyInAnyOrder("username", "email", "password");
    }
}
