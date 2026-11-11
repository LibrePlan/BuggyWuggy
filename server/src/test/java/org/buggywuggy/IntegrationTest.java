package org.buggywuggy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for HTTP-level integration tests against a running server and a real database.
 * Every test starts without users, sessions or custom groups.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestDatabaseConfiguration.class)
public abstract class IntegrationTest {

    protected static final String SETUP_TOKEN = "test-setup-token";
    protected static final String ADMIN = "admin";
    protected static final String ADMIN_PASSWORD = "admin-password-123";

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetUsers() {
        jdbc.update("delete from spring_session");
        jdbc.update("delete from user_account");
        jdbc.update("delete from user_group where name not in ('administrators', 'users')");
    }

    protected ApiClient client() {
        return new ApiClient(port);
    }

    /** Runs first-time setup and returns a client logged in as the system administrator. */
    protected ApiClient setupAndLoginAdmin() {
        ApiClient client = client();
        var setup = client.post("/api/v1/setup/admin", """
                {"setupToken": "%s", "username": "%s", "displayName": "Administrator",
                 "email": "admin@example.org", "password": "%s"}"""
                .formatted(SETUP_TOKEN, ADMIN, ADMIN_PASSWORD));
        assertThat(setup.status()).as(setup.toString()).isEqualTo(201);
        assertThat(client.login(ADMIN, ADMIN_PASSWORD).status()).isEqualTo(200);
        return client;
    }

    /** Creates a regular user through the admin API and returns its id. */
    protected long createUser(ApiClient admin, String username, String password) {
        var response = admin.post("/api/v1/admin/users", """
                {"username": "%s", "displayName": "%s", "email": "%s@example.org", "password": "%s"}"""
                .formatted(username, username, username, password));
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return ((Number) response.json("$.id")).longValue();
    }

    protected ApiClient loginAs(String username, String password) {
        ApiClient client = client();
        var response = client.login(username, password);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return client;
    }
}
