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
 * Every test starts without users, sessions or custom groups, and with the default global
 * permissions.
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
        jdbc.update("delete from project");
        jdbc.update("delete from user_account");
        jdbc.update("delete from user_group where name not in ('administrators', 'users')");
        // The global permissions as V9__seed_defaults.sql grants them: a test may have revoked one.
        jdbc.update("delete from global_permission_grant");
        jdbc.update("""
                insert into global_permission_grant (permission, group_id)
                select p.permission, g.id
                from (values ('ADMINISTER', 'administrators'),
                             ('CREATE_PROJECT', 'administrators'),
                             ('BROWSE_USERS', 'users')) as p (permission, group_name)
                join user_group g on g.name = p.group_name""");
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

    /** Creates a project through the API and returns its id. */
    protected long createProject(ApiClient client, String key, String name) {
        var response = client.post("/api/v1/projects", """
                {"key": "%s", "name": "%s"}""".formatted(key, name));
        assertThat(response.status()).as(response.toString()).isEqualTo(201);
        return ((Number) response.json("$.id")).longValue();
    }

    protected long roleId(String name) {
        return jdbc.queryForObject("select id from project_role where name = ?", Long.class, name);
    }

    protected long groupId(String name) {
        return jdbc.queryForObject("select id from user_group where name = ?", Long.class, name);
    }

    protected ApiClient loginAs(String username, String password) {
        ApiClient client = client();
        var response = client.login(username, password);
        assertThat(response.status()).as(response.toString()).isEqualTo(200);
        return client;
    }
}
