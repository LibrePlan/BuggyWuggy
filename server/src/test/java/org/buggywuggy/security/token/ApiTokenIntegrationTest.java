/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;

/** Personal API tokens: made in a session, used with Authorization: Bearer. */
class ApiTokenIntegrationTest extends IntegrationTest {

    private ApiClient withToken(String secret) {
        return client().withBearer(secret);
    }

    private static String code(ApiClient.Response response) {
        return response.json("$.code");
    }

    @Test
    void aTokenWorksWithTheRightsOfItsUserUntilItIsRemoved() {
        ApiClient admin = setupAndLoginAdmin();
        long otto = createUser(admin, "otto", "otto-password-12");
        ApiClient session = loginAs("otto", "otto-password-12");

        var created = session.post("/api/v1/myself/tokens", """
                {"name": "Backup script", "readOnly": false}""");
        assertThat(created.status()).as(created.toString()).isEqualTo(200);
        String secret = created.json("$.secret");
        assertThat(secret).startsWith(ApiTokenService.PREFIX).hasSizeGreaterThan(40);
        assertThat(secret).startsWith(created.<String>json("$.token.prefix"));
        long id = ((Number) created.json("$.token.id")).longValue();

        // Only a hash is stored, and the list never shows the token.
        assertThat(jdbc.queryForObject("select count(*) from api_token where token_hash = ?", Integer.class,
                ApiTokenService.hash(secret))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from api_token where token_hash = ? or prefix = ?",
                Integer.class, secret, secret)).isZero();
        var list = session.get("/api/v1/myself/tokens");
        assertThat(list.body()).contains("Backup script").doesNotContain(secret);

        // Reading and writing as otto, without session or CSRF token.
        ApiClient script = withToken(secret);
        assertThat(script.get("/api/v1/myself").<String>json("$.username")).isEqualTo("otto");
        var changed = script.patch("/api/v1/myself", """
                {"displayName": "Otto by script"}""");
        assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
        assertThat(script.cookie("SESSION")).isEmpty();
        assertThat(jdbc.queryForObject("select last_used_at is not null from api_token where id = ?", Boolean.class,
                id)).isTrue();
        // Otto's rights, nothing more.
        assertThat(script.get("/api/v1/admin/users").status()).isEqualTo(403);
        // A token does not make or remove tokens.
        var byToken = script.post("/api/v1/myself/tokens", """
                {"name": "Another", "readOnly": false}""");
        assertThat(byToken.status()).isEqualTo(403);
        assertThat(code(byToken)).isEqualTo("token.session-required");
        assertThat(script.delete("/api/v1/myself/tokens/" + id).status()).isEqualTo(403);

        // A wrong token is refused, not treated as not logged in.
        var wrong = withToken(secret + "x").get("/api/v1/myself");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(code(wrong)).isEqualTo("token.invalid");
        assertThat(withToken("not-a-token").get("/api/v1/version").status()).isEqualTo(401);

        // Not for an inactive user.
        jdbc.update("update user_account set active = false where id = ?", otto);
        assertThat(script.get("/api/v1/myself").status()).isEqualTo(401);
        jdbc.update("update user_account set active = true where id = ?", otto);
        assertThat(script.get("/api/v1/myself").status()).isEqualTo(200);

        // Removed: refused.
        assertThat(session.delete("/api/v1/myself/tokens/" + id).status()).isEqualTo(204);
        assertThat(script.get("/api/v1/myself").status()).isEqualTo(401);
        assertThat(session.delete("/api/v1/myself/tokens/" + id).status()).isEqualTo(404);
    }

    @Test
    void aReadOnlyTokenOnlyReadsAndAnExpiredOneNothing() {
        setupAndLoginAdmin();
        ApiClient session = loginAs(ADMIN, ADMIN_PASSWORD);
        var created = session.post("/api/v1/myself/tokens", """
                {"name": "Report", "readOnly": true, "expiresOn": "%s"}""".formatted(LocalDate.now().plusDays(30)));
        assertThat(created.status()).as(created.toString()).isEqualTo(200);
        ApiClient script = withToken(created.json("$.secret"));

        assertThat(script.get("/api/v1/admin/users").status()).isEqualTo(200);
        var write = script.patch("/api/v1/myself", """
                {"displayName": "Changed"}""");
        assertThat(write.status()).isEqualTo(403);
        assertThat(code(write)).isEqualTo("token.read-only");

        jdbc.update("update api_token set expires_on = current_date - 1");
        assertThat(script.get("/api/v1/myself").status()).isEqualTo(401);

        var past = session.post("/api/v1/myself/tokens", """
                {"name": "Old", "readOnly": true, "expiresOn": "%s"}""".formatted(LocalDate.now().minusDays(1)));
        assertThat(past.status()).isEqualTo(400);
        assertThat(code(past)).isEqualTo("token.expiry-past");
        assertThat(session.post("/api/v1/myself/tokens", """
                {"name": " ", "readOnly": true}""").status()).isEqualTo(400);
    }

    @Test
    void administratorsSeeAndRemoveTheTokensOfAUser() {
        ApiClient admin = setupAndLoginAdmin();
        long otto = createUser(admin, "otto", "otto-password-12");
        ApiClient session = loginAs("otto", "otto-password-12");
        String secret = session.post("/api/v1/myself/tokens", """
                {"name": "Sync", "readOnly": false}""").json("$.secret");
        long id = ((Number) admin.get("/api/v1/admin/users/" + otto + "/tokens").json("$[0].id")).longValue();
        assertThat(admin.get("/api/v1/admin/users/" + otto + "/tokens").<String>json("$[0].name")).isEqualTo("Sync");

        // Another user's token is not one's own.
        assertThat(admin.delete("/api/v1/myself/tokens/" + id).status()).isEqualTo(404);
        assertThat(session.get("/api/v1/admin/users/" + otto + "/tokens").status()).isEqualTo(403);

        assertThat(admin.delete("/api/v1/admin/users/" + otto + "/tokens/" + id).status()).isEqualTo(204);
        assertThat(withToken(secret).get("/api/v1/myself").status()).isEqualTo(401);
        // Removing the user removes the tokens.
        session.post("/api/v1/myself/tokens", """
                {"name": "Sync 2", "readOnly": false}""");
        jdbc.update("delete from user_account where id = ?", otto);
        assertThat(jdbc.queryForObject("select count(*) from api_token", Integer.class)).isZero();
    }
}
