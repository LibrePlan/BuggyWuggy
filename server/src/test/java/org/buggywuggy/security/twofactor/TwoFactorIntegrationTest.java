/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Logging in with two-factor login switched on. */
@TestPropertySource(properties = "buggywuggy.security.two-factor.enabled=true")
class TwoFactorIntegrationTest extends IntegrationTest {

    private record Enrolled(ApiClient client, byte[] secret, List<String> recoveryCodes) {
    }

    private static String code(byte[] secret, int stepsFromNow) {
        return Totp.code(secret, Totp.step(Instant.now()) + stepsFromNow);
    }

    /** Creates the administrator and goes through the enrollment wizard. */
    private Enrolled setupAdmin() {
        var setup = client().post("/api/v1/setup/admin", """
                {"setupToken": "%s", "username": "%s", "displayName": "Administrator",
                 "email": "admin@example.org", "password": "%s"}"""
                .formatted(SETUP_TOKEN, ADMIN, ADMIN_PASSWORD));
        assertThat(setup.status()).as(setup.toString()).isEqualTo(201);
        return enroll(ADMIN, ADMIN_PASSWORD);
    }

    private Enrolled enroll(String username, String password) {
        ApiClient client = client();
        var login = client.login(username, password);
        assertThat(login.status()).as(login.toString()).isEqualTo(200);
        assertThat(login.<String>json("$.step")).isEqualTo("TWO_FACTOR_ENROLLMENT");

        var enrollment = client.get("/api/v1/auth/two-factor/enrollment");
        assertThat(enrollment.status()).as(enrollment.toString()).isEqualTo(200);
        byte[] secret = Base32.decode(enrollment.json("$.secret"));
        var result = client.post("/api/v1/auth/two-factor/enrollment", """
                {"code": "%s"}""".formatted(code(secret, 0)));
        assertThat(result.status()).as(result.toString()).isEqualTo(200);
        assertThat(result.<String>json("$.user.username")).isEqualTo(username);
        return new Enrolled(client, secret, result.json("$.recoveryCodes"));
    }

    private ApiClient passwordOnly(String username, String password) {
        ApiClient client = client();
        var login = client.login(username, password);
        assertThat(login.status()).as(login.toString()).isEqualTo(200);
        assertThat(login.<String>json("$.step")).isEqualTo("TWO_FACTOR");
        return client;
    }

    @Test
    void enrollmentWizardShowsAQrCodeAndChecksACode() {
        var setup = client().post("/api/v1/setup/admin", """
                {"setupToken": "%s", "username": "%s", "displayName": "Administrator",
                 "email": "admin@example.org", "password": "%s"}"""
                .formatted(SETUP_TOKEN, ADMIN, ADMIN_PASSWORD));
        assertThat(setup.status()).isEqualTo(201);

        ApiClient client = client();
        var login = client.login(ADMIN, ADMIN_PASSWORD);
        assertThat(login.<String>json("$.step")).isEqualTo("TWO_FACTOR_ENROLLMENT");
        assertThat(login.<Object>json("$.user")).isNull();
        // The password alone does not log in.
        assertThat(client.get("/api/v1/myself").status()).isEqualTo(401);
        assertThat(client.post("/api/v1/auth/two-factor", """
                {"code": "123456"}""").status()).isEqualTo(400);

        var enrollment = client.get("/api/v1/auth/two-factor/enrollment");
        assertThat(enrollment.<String>json("$.uri"))
                .startsWith("otpauth://totp/BuggyWuggy:admin?secret=" + enrollment.json("$.secret"))
                .contains("&issuer=BuggyWuggy");
        assertThat(enrollment.<String>json("$.qrCodeSvg")).startsWith("<svg");
        // The same secret until the wizard is done.
        assertThat(client.get("/api/v1/auth/two-factor/enrollment").<String>json("$.secret"))
                .isEqualTo(enrollment.json("$.secret"));
        byte[] secret = Base32.decode(enrollment.json("$.secret"));

        var wrong = client.post("/api/v1/auth/two-factor/enrollment", """
                {"code": "%s"}""".formatted(code(secret, 5)));
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(client.get("/api/v1/myself").status()).isEqualTo(401);

        var right = client.post("/api/v1/auth/two-factor/enrollment", """
                {"code": "%s"}""".formatted(code(secret, 0)));
        assertThat(right.status()).as(right.toString()).isEqualTo(200);
        List<String> recoveryCodes = right.json("$.recoveryCodes");
        assertThat(recoveryCodes).hasSize(10).doesNotHaveDuplicates()
                .allMatch(c -> c.matches("[A-Z2-7]{5}-[A-Z2-7]{5}"));
        assertThat(client.get("/api/v1/myself").status()).isEqualTo(200);

        // Stored encrypted, the recovery codes only as hashes.
        byte[] stored = jdbc.queryForObject("select secret from user_two_factor", byte[].class);
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(enrollment.<String>json("$.secret"));
        assertThat(jdbc.queryForObject("select count(*) from user_recovery_code where used_at is null", Integer.class))
                .isEqualTo(10);
    }

    @Test
    void loginNeedsACodeFromTheAppOnlyOnceEach() {
        Enrolled admin = setupAdmin();
        admin.client().logout();
        assertThat(admin.client().get("/api/v1/myself").status()).isEqualTo(401);

        ApiClient client = passwordOnly(ADMIN, ADMIN_PASSWORD);
        assertThat(client.get("/api/v1/myself").status()).isEqualTo(401);
        // A code of a step that was used already is refused; the next one works.
        long step = Totp.step(Instant.now());
        jdbc.update("update user_two_factor set last_step = ?", step);
        assertThat(client.post("/api/v1/auth/two-factor", """
                {"code": "%s"}""".formatted(Totp.code(admin.secret(), step))).status()).isEqualTo(401);
        String next = Totp.code(admin.secret(), step + 1);
        var login = client.post("/api/v1/auth/two-factor", """
                {"code": "%s"}""".formatted(next.substring(0, 3) + " " + next.substring(3)));
        assertThat(login.status()).as(login.toString()).isEqualTo(200);
        assertThat(login.<String>json("$.user.username")).isEqualTo(ADMIN);
        assertThat(login.<Boolean>json("$.recoveryCodeUsed")).isFalse();
        assertThat(client.get("/api/v1/myself").status()).isEqualTo(200);

        // The same code again, in another browser.
        ApiClient other = passwordOnly(ADMIN, ADMIN_PASSWORD);
        assertThat(other.post("/api/v1/auth/two-factor", """
                {"code": "%s"}""".formatted(next)).status()).isEqualTo(401);
    }

    @Test
    void recoveryCodesWorkOnce() {
        Enrolled admin = setupAdmin();
        String recovery = admin.recoveryCodes().get(3);

        ApiClient client = passwordOnly(ADMIN, ADMIN_PASSWORD);
        var login = client.post("/api/v1/auth/two-factor", """
                {"code": "%s"}""".formatted(recovery.toLowerCase().replace("-", " ")));
        assertThat(login.status()).as(login.toString()).isEqualTo(200);
        assertThat(login.<Boolean>json("$.recoveryCodeUsed")).isTrue();
        assertThat(login.<Integer>json("$.recoveryCodesLeft")).isEqualTo(9);

        ApiClient again = passwordOnly(ADMIN, ADMIN_PASSWORD);
        assertThat(again.post("/api/v1/auth/two-factor", """
                {"code": "%s"}""".formatted(recovery)).status()).isEqualTo(401);
    }

    @Test
    void tooManyWrongCodesEndTheLogin() {
        Enrolled admin = setupAdmin();
        createUser(admin.client(), "guesser", "guesser-password");
        enroll("guesser", "guesser-password");

        ApiClient client = passwordOnly("guesser", "guesser-password");
        for (int i = 0; i < 4; i++) {
            assertThat(client.post("/api/v1/auth/two-factor", """
                    {"code": "000000"}""").status()).isEqualTo(401);
        }
        assertThat(client.post("/api/v1/auth/two-factor", """
                {"code": "000000"}""").status()).isEqualTo(429);
        // Neither the code nor the password helps until the throttle window has passed.
        assertThat(client.post("/api/v1/auth/two-factor", """
                {"code": "000000"}""").status()).isEqualTo(401);
        assertThat(client().login("guesser", "guesser-password").status()).isEqualTo(429);
    }

    @Test
    void anAdministratorResetsTheSecondFactor() {
        Enrolled admin = setupAdmin();
        long bob = createUser(admin.client(), "bob", "bob-password-123");
        assertThat(admin.client().get("/api/v1/admin/users/" + bob).<Boolean>json("$.twoFactorEnrolled")).isFalse();
        Enrolled bobs = enroll("bob", "bob-password-123");
        assertThat(admin.client().get("/api/v1/admin/users/" + bob).<Boolean>json("$.twoFactorEnrolled")).isTrue();
        assertThat(bobs.client().get("/api/v1/myself").status()).isEqualTo(200);

        // Bob may not reset it himself.
        assertThat(bobs.client().delete("/api/v1/admin/users/" + bob + "/two-factor").status()).isEqualTo(403);

        assertThat(admin.client().delete("/api/v1/admin/users/" + bob + "/two-factor").status()).isEqualTo(204);
        assertThat(admin.client().get("/api/v1/admin/users/" + bob).<Boolean>json("$.twoFactorEnrolled")).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from user_recovery_code", Integer.class)).isEqualTo(10);
        // Bob's session ended; his next login sets up a new second factor.
        assertThat(bobs.client().get("/api/v1/myself").status()).isEqualTo(401);
        Enrolled again = enroll("bob", "bob-password-123");
        assertThat(again.secret()).isNotEqualTo(bobs.secret());
    }

    @Test
    void aPasswordWithoutSecondFactorIsNoLogin() {
        Enrolled admin = setupAdmin();
        ApiClient pending = passwordOnly(ADMIN, ADMIN_PASSWORD);
        assertThat(pending.get("/api/v1/myself").status()).isEqualTo(401);
        assertThat(pending.get("/api/v1/projects").status()).isEqualTo(401);
        assertThat(pending.get("/api/v1/auth/two-factor/enrollment").status()).isEqualTo(400);
        assertThat(client().post("/api/v1/auth/two-factor", """
                {"code": "123456"}""").status()).isEqualTo(401);
        assertThat(admin.client().get("/api/v1/myself").status()).isEqualTo(200);
    }

    @Test
    void anApiTokenNeedsNoSecondFactor() {
        Enrolled admin = setupAdmin();
        var created = admin.client().post("/api/v1/myself/tokens", """
                {"name": "Script", "readOnly": true}""");
        assertThat(created.status()).as(created.toString()).isEqualTo(200);
        ApiClient script = client().withBearer(created.json("$.secret"));
        assertThat(script.get("/api/v1/myself").<String>json("$.username")).isEqualTo(ADMIN);
        // A password-only login (second factor still to come) makes no tokens.
        ApiClient half = passwordOnly(ADMIN, ADMIN_PASSWORD);
        assertThat(half.post("/api/v1/myself/tokens", """
                {"name": "Sneaky", "readOnly": true}""").status()).isEqualTo(401);
    }
}
