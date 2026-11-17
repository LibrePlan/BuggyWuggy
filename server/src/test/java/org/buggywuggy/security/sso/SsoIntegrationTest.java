/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.buggywuggy.ApiClient;
import org.buggywuggy.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Single sign-on against a real OpenID Connect provider (see {@link OidcTestProvider}), with
 * two providers that point at it: "open" creates accounts, links by verified e-mail address and
 * synchronises groups; "strict" only links accounts an administrator made, by username.
 * Password login is for system administrators only.
 */
class SsoIntegrationTest extends IntegrationTest {

    static final OidcTestProvider IDP = OidcTestProvider.get();

    @DynamicPropertySource
    static void sso(DynamicPropertyRegistry registry) {
        String prefix = "buggywuggy.security.sso.";
        registry.add(prefix + "password-login", () -> "system-admins");
        for (String id : List.of("open", "strict")) {
            String p = prefix + "providers." + id + ".";
            registry.add(p + "issuer-uri", IDP::issuer);
            registry.add(p + "client-id", () -> OidcTestProvider.CLIENT_ID);
            registry.add(p + "client-secret", () -> OidcTestProvider.CLIENT_SECRET);
        }
        String open = prefix + "providers.open.";
        registry.add(open + "display-name", () -> "Open IdP");
        registry.add(open + "create-users", () -> "true");
        registry.add(open + "link-users-by", () -> "email");
        registry.add(open + "groups-claim", () -> "groups");
        registry.add(open + "group-mapping[0].idp-group", () -> "buggywuggy-admins");
        registry.add(open + "group-mapping[0].group", () -> "administrators");
        registry.add(open + "group-mapping[1].idp-group", () -> "staff");
        registry.add(open + "group-mapping[1].group", () -> "staff");
        String strict = prefix + "providers.strict.";
        registry.add(strict + "display-name", () -> "Strict IdP");
        registry.add(strict + "create-users", () -> "false");
        registry.add(strict + "link-users-by", () -> "username");
    }

    /** Logs in through the provider, as a browser; returns where BuggyWuggy sends the browser. */
    static String sso(ApiClient client, String provider, String user, String next) {
        var start = client.redirect("/oauth2/authorization/" + provider
                + (next == null ? "" : "?next=" + URLEncoder.encode(next, StandardCharsets.UTF_8)));
        assertThat(start.status()).isEqualTo(302);
        if (!start.location().startsWith(IDP.issuer())) {
            return start.location();
        }
        URI back = IDP.authenticate(URI.create(start.location()), user);
        var end = client.redirect(back.toString());
        assertThat(end.status()).as(end.toString()).isEqualTo(302);
        return end.location();
    }

    private long userId(String username) {
        return jdbc.queryForObject("select id from user_account where username = ?", Long.class, username);
    }

    private List<String> groupsOf(String username) {
        return jdbc.queryForList("""
                select g.name from user_group g join group_membership m on m.group_id = g.id
                join user_account u on u.id = m.user_id where u.username = ? order by g.name""", String.class, username);
    }

    @Test
    void theLoginPageListsTheProviders() {
        var sso = client().get("/api/v1/auth/sso");
        assertThat(sso.status()).isEqualTo(200);
        assertThat(sso.<String>json("$.passwordLogin")).isEqualTo("SYSTEM_ADMINS");
        assertThat(sso.<List<String>>json("$.providers[*].id")).containsExactly("open", "strict");
        assertThat(sso.<List<String>>json("$.providers[*].name")).containsExactly("Open IdP", "Strict IdP");
    }

    @Test
    void createsAnAccountAtTheFirstLoginWithTheProvidersGroups() {
        setupAndLoginAdmin();
        jdbc.update("insert into user_group (name) values ('staff')");

        ApiClient alice = client();
        assertThat(sso(alice, "open", "alice", "/projects")).isEqualTo("/projects");
        var myself = alice.get("/api/v1/myself");
        assertThat(myself.status()).isEqualTo(200);
        assertThat(myself.<String>json("$.username")).isEqualTo("alice");
        assertThat(myself.<String>json("$.displayName")).isEqualTo("Alice Adams");
        assertThat(myself.<String>json("$.email")).isEqualTo("alice@example.org");
        assertThat(myself.<Boolean>json("$.hasPassword")).isFalse();
        assertThat(myself.<List<String>>json("$.globalPermissions")).contains("ADMINISTER");
        assertThat(groupsOf("alice")).containsExactly("administrators", "staff", "users");

        // The next login finds the same account through the identity.
        assertThat(sso(client(), "open", "alice", null)).isEqualTo("/");
        assertThat(jdbc.queryForObject("select count(*) from user_account where username = 'alice'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from user_identity", Integer.class)).isEqualTo(1);
    }

    @Test
    void linksAnExistingAccountByVerifiedEmailAndOnlyChangesMappedGroups() {
        ApiClient admin = setupAndLoginAdmin();
        jdbc.update("insert into user_group (name) values ('staff'), ('testers')");
        long bob = createUser(admin, "bobby", "bob-local-password");
        admin.patch("/api/v1/admin/users/" + bob, """
                {"email": "bob@example.org"}""");
        for (String group : List.of("administrators", "testers")) {
            assertThat(admin.put("/api/v1/admin/groups/" + groupId(group) + "/members/" + bob, null).status())
                    .isIn(200, 204);
        }

        ApiClient client = client();
        assertThat(sso(client, "open", "bob", null)).isEqualTo("/");
        assertThat(client.get("/api/v1/myself").<String>json("$.username")).isEqualTo("bobby");
        // administrators is mapped and bob is not in buggywuggy-admins; testers is not mapped.
        assertThat(groupsOf("bobby")).containsExactly("staff", "testers", "users");

        var identities = admin.get("/api/v1/admin/users/" + bob + "/sso-identities");
        assertThat(identities.<List<String>>json("$[*].provider")).containsExactly("open");
        assertThat(identities.<List<String>>json("$[*].providerName")).containsExactly("Open IdP");
        assertThat(identities.<List<String>>json("$[*].email")).containsExactly("bob@example.org");
    }

    @Test
    void anUnverifiedEmailAddressIsNotLinked() {
        ApiClient admin = setupAndLoginAdmin();
        long carol = createUser(admin, "carol-local", "carol-local-password");
        admin.patch("/api/v1/admin/users/" + carol, """
                {"email": "carol@example.org"}""");

        ApiClient client = client();
        assertThat(sso(client, "open", "carol", null)).isEqualTo("/login?sso-error=sso.email-not-verified");
        assertThat(client.get("/api/v1/myself").status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from user_identity", Integer.class)).isZero();
    }

    @Test
    void theStrictProviderOnlyLetsInAccountsAnAdministratorMade() {
        ApiClient admin = setupAndLoginAdmin();
        ApiClient alice = client();
        assertThat(sso(alice, "strict", "alice", null)).isEqualTo("/login?sso-error=sso.no-account");
        assertThat(alice.get("/api/v1/myself").status()).isEqualTo(401);

        // An account for single sign-on only, without password.
        var created = admin.post("/api/v1/admin/users", """
                {"username": "alice", "displayName": "Alice", "email": "alice@example.org"}""");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        assertThat(created.<Boolean>json("$.hasPassword")).isFalse();

        assertThat(sso(alice, "strict", "alice", null)).isEqualTo("/");
        assertThat(alice.get("/api/v1/myself").<String>json("$.username")).isEqualTo("alice");
        // Without single sign-on providers this would be refused (user.password-required).
        assertThat(groupsOf("alice")).containsExactly("users");
    }

    @Test
    void aPlaceholderFromImportedIssuesBecomesTheAccount() {
        jdbc.update("""
                insert into user_account (username, display_name, active, placeholder, created_at)
                values ('dave', 'dave (imported)', false, true, now())""");

        ApiClient dave = client();
        assertThat(sso(dave, "strict", "dave", null)).isEqualTo("/");
        var myself = dave.get("/api/v1/myself");
        assertThat(myself.<String>json("$.displayName")).isEqualTo("Dave Davis");
        assertThat(myself.<String>json("$.email")).isEqualTo("dave@example.org");
        assertThat(jdbc.queryForObject("select placeholder from user_account where username = 'dave'", Boolean.class))
                .isFalse();
        assertThat(groupsOf("dave")).containsExactly("users");
    }

    @Test
    void aDeactivatedAccountCannotLogIn() {
        ApiClient admin = setupAndLoginAdmin();
        ApiClient dave = client();
        assertThat(sso(dave, "open", "dave", null)).isEqualTo("/");
        assertThat(admin.patch("/api/v1/admin/users/" + userId("dave"), """
                {"active": false}""").status()).isEqualTo(200);
        assertThat(dave.get("/api/v1/myself").status()).isEqualTo(401);
        assertThat(sso(client(), "open", "dave", null)).isEqualTo("/login?sso-error=sso.inactive");
    }

    @Test
    void anAdministratorUnlinksAnIdentity() {
        ApiClient admin = setupAndLoginAdmin();
        ApiClient alice = client();
        sso(alice, "open", "alice", null);
        long id = userId("alice");

        assertThat(admin.delete("/api/v1/admin/users/" + id + "/sso-identities/open").status()).isEqualTo(204);
        assertThat(admin.delete("/api/v1/admin/users/" + id + "/sso-identities/open").status()).isEqualTo(404);
        assertThat(alice.get("/api/v1/myself").status()).isEqualTo(401);
        // The next login is a first login again: linked by the verified e-mail address.
        assertThat(sso(client(), "open", "alice", null)).isEqualTo("/");
        assertThat(userId("alice")).isEqualTo(id);
    }

    @Test
    void passwordLoginIsForSystemAdministratorsOnly() {
        ApiClient admin = setupAndLoginAdmin();
        createUser(admin, "erin", "erin-password-123");

        var erin = client().login("erin", "erin-password-123");
        assertThat(erin.status()).isEqualTo(401);
        assertThat(erin.body()).contains("auth.bad-credentials-admins-only");
        // A wrong password gets the same answer.
        var wrong = client().login(ADMIN, "not-the-password");
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.body()).contains("auth.bad-credentials-admins-only");
    }

    @Test
    void loggingOutAlsoLogsOutAtTheProvider() throws Exception {
        ApiClient alice = client();
        sso(alice, "open", "alice", null);

        var logout = alice.logout();
        assertThat(logout.status()).isEqualTo(200);
        String url = logout.json("$.redirectUrl");
        assertThat(url).startsWith(IDP.issuer()).contains("id_token_hint=").contains("client_id=buggywuggy");
        assertThat(URLDecoder.decode(url, StandardCharsets.UTF_8))
                .contains("post_logout_redirect_uri=http://localhost:" + port + "/login");
        assertThat(alice.get("/api/v1/myself").status()).isEqualTo(401);

        // The provider accepts it and sends the browser back to the login page.
        try (HttpClient browser = HttpClient.newHttpClient()) {
            var atProvider = browser.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), BodyHandlers.ofString());
            assertThat(atProvider.statusCode()).as(atProvider.body()).isIn(200, 302);
            if (atProvider.statusCode() == 302) {
                assertThat(atProvider.headers().firstValue("Location").orElseThrow())
                        .isEqualTo("http://localhost:" + port + "/login");
            }
        }

        // A password login has nothing to log out of at a provider.
        ApiClient admin = setupAndLoginAdmin();
        assertThat(admin.logout().status()).isEqualTo(204);
    }

    @Test
    void refusesUnknownProvidersAndForeignRedirects() {
        assertThat(client().redirect("/oauth2/authorization/nope").location())
                .isEqualTo("/login?sso-error=sso.unknown-provider");
        assertThat(sso(client(), "open", "alice", "https://evil.example/")).isEqualTo("/");
        assertThat(sso(client(), "open", "alice", "//evil.example/")).isEqualTo("/");
    }

    @Test
    void aCancelledLoginGoesBackToTheLoginPage() {
        assumeTrue(IDP instanceof MockOidcProvider, "only the mock provider can cancel");
        assertThat(sso(client(), "open", "cancel", null)).isEqualTo("/login?sso-error=sso.cancelled");
    }
}
