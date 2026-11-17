/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * An OpenID Connect provider for the single sign-on tests: Keycloak in a container (the
 * default), or with {@code BUGGYWUGGY_TEST_OIDC=mock} a small in-process provider, for build
 * machines where pulling Keycloak costs too much (the Forgejo runners).
 *
 * <p>Both know the same users, each with password {@code <username>-password}:
 * <ul>
 * <li>alice — alice@example.org (verified), groups staff and buggywuggy-admins</li>
 * <li>bob — bob@example.org (verified), group staff</li>
 * <li>carol — carol@example.org (<em>not</em> verified), no groups</li>
 * <li>dave — dave@example.org (verified), no groups</li>
 * </ul>
 */
public interface OidcTestProvider {

    String CLIENT_ID = "buggywuggy";
    String CLIENT_SECRET = "test-secret";

    record TestUser(String username, String firstName, String lastName, String email, boolean emailVerified,
            List<String> groups) {

        String password() {
            return username + "-password";
        }

        String name() {
            return firstName + " " + lastName;
        }
    }

    List<TestUser> USERS = List.of(
            new TestUser("alice", "Alice", "Adams", "alice@example.org", true, List.of("staff", "buggywuggy-admins")),
            new TestUser("bob", "Bob", "Brown", "bob@example.org", true, List.of("staff")),
            new TestUser("carol", "Carol", "Clark", "carol@example.org", false, List.of()),
            new TestUser("dave", "Dave", "Davis", "dave@example.org", true, List.of()));

    String issuer();

    /**
     * Logs {@code username} in at the provider, in a browser of its own, for the authorization
     * request BuggyWuggy sent the browser to; returns where the provider sends the browser back.
     */
    URI authenticate(URI authorizationRequest, String username);

    /** The provider of this test run, started once. */
    static OidcTestProvider get() {
        return Holder.INSTANCE;
    }

    final class Holder {

        static final OidcTestProvider INSTANCE = start();

        private Holder() {
        }

        private static OidcTestProvider start() {
            String kind = System.getProperty("buggywuggy.test.oidc", System.getenv().getOrDefault("BUGGYWUGGY_TEST_OIDC", "keycloak"));
            return switch (kind.toLowerCase(Locale.ROOT)) {
                case "mock" -> new MockOidcProvider();
                case "keycloak" -> new KeycloakOidcProvider();
                default -> throw new IllegalStateException("BUGGYWUGGY_TEST_OIDC must be keycloak or mock, not " + kind);
            };
        }
    }
}
