/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Keycloak in a container, with the realm {@code buggywuggy} from
 * {@code src/test/resources/sso/keycloak-realm.json}. Logging in fills in Keycloak's own login
 * form, as a browser would.
 */
final class KeycloakOidcProvider implements OidcTestProvider {

    static final String IMAGE = "quay.io/keycloak/keycloak:26.8.1";
    private static final Pattern LOGIN_FORM = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*>");
    private static final Pattern ACTION = Pattern.compile("action=\"([^\"]+)\"");

    private final String issuer;

    @SuppressWarnings("resource") // runs until the test JVM ends; Testcontainers removes it
    KeycloakOidcProvider() {
        GenericContainer<?> keycloak = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(8080)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                .withCopyFileToContainer(MountableFile.forClasspathResource("sso/keycloak-realm.json"),
                        "/opt/keycloak/data/import/buggywuggy-realm.json")
                .withCommand("start-dev", "--import-realm")
                .waitingFor(Wait.forHttp("/realms/buggywuggy/.well-known/openid-configuration")
                        .forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));
        keycloak.start();
        issuer = "http://localhost:" + keycloak.getMappedPort(8080) + "/realms/buggywuggy";
    }

    @Override
    public String issuer() {
        return issuer;
    }

    @Override
    public URI authenticate(URI authorizationRequest, String username) {
        // Keycloak marks its cookies Secure, also over http; browsers send them to localhost
        // anyway, Java's CookieManager does not. So they are passed on by hand.
        try (HttpClient browser = HttpClient.newHttpClient()) {
            var page = browser.send(HttpRequest.newBuilder(authorizationRequest).GET().build(), BodyHandlers.ofString());
            Matcher form = LOGIN_FORM.matcher(page.body());
            if (page.statusCode() != 200 || !form.find()) {
                throw new IllegalStateException("No Keycloak login form: " + page.statusCode() + " " + page.body());
            }
            Matcher action = ACTION.matcher(form.group());
            if (!action.find()) {
                throw new IllegalStateException("Keycloak login form without action: " + form.group());
            }
            String cookies = page.headers().allValues("Set-Cookie").stream()
                    .map(c -> c.substring(0, c.indexOf(';') < 0 ? c.length() : c.indexOf(';')))
                    .collect(Collectors.joining("; "));
            String body = "username=" + enc(username) + "&password=" + enc(username + "-password") + "&credentialId=";
            var login = browser.send(HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Cookie", cookies)
                    .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
            if (login.statusCode() != 302) {
                throw new IllegalStateException("Keycloak login failed: " + login.statusCode() + " " + login.body());
            }
            return URI.create(login.headers().firstValue("Location").orElseThrow());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
