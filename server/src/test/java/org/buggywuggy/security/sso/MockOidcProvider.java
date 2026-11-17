/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A minimal OpenID Connect provider in the test JVM: authorization code flow with PKCE,
 * RS256-signed ID tokens, userinfo and an end-session endpoint. The "login" is done by adding
 * {@code test_user} to the authorization request; {@code test_user=cancel} answers
 * {@code access_denied}, as when a user cancels.
 */
final class MockOidcProvider implements OidcTestProvider {

    private record Pending(TestUser user, String nonce, String redirectUri, String codeChallenge) {
    }

    private final HttpServer server;
    private final String issuer;
    private final RSAKey key;
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private final Map<String, TestUser> accessTokens = new ConcurrentHashMap<>();

    MockOidcProvider() {
        try {
            key = new RSAKeyGenerator(2048).keyID("mock").generate();
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException | JOSEException e) {
            throw new IllegalStateException(e);
        }
        issuer = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/.well-known/openid-configuration", this::discovery);
        server.createContext("/authorize", this::authorize);
        server.createContext("/token", this::token);
        server.createContext("/userinfo", this::userinfo);
        server.createContext("/jwks", ex -> json(ex, 200, new JWKSet(key.toPublicJWK()).toJSONObject()));
        server.createContext("/logout", ex -> send(ex, 200, "text/plain", "logged out"));
        server.start();
    }

    @Override
    public String issuer() {
        return issuer;
    }

    @Override
    public URI authenticate(URI authorizationRequest, String username) {
        URI withUser = URI.create(authorizationRequest + "&test_user=" + username);
        try (HttpClient browser = HttpClient.newHttpClient()) {
            var response = browser.send(HttpRequest.newBuilder(withUser).GET().build(), BodyHandlers.ofString());
            if (response.statusCode() != 302) {
                throw new IllegalStateException("Mock login failed: " + response.statusCode() + " " + response.body());
            }
            return URI.create(response.headers().firstValue("Location").orElseThrow());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // --- endpoints -----------------------------------------------------------------------

    private void discovery(HttpExchange ex) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("issuer", issuer);
        doc.put("authorization_endpoint", issuer + "/authorize");
        doc.put("token_endpoint", issuer + "/token");
        doc.put("userinfo_endpoint", issuer + "/userinfo");
        doc.put("jwks_uri", issuer + "/jwks");
        doc.put("end_session_endpoint", issuer + "/logout");
        doc.put("response_types_supported", List.of("code"));
        doc.put("subject_types_supported", List.of("public"));
        doc.put("id_token_signing_alg_values_supported", List.of("RS256"));
        doc.put("scopes_supported", List.of("openid", "profile", "email"));
        doc.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
        doc.put("code_challenge_methods_supported", List.of("S256"));
        json(ex, 200, doc);
    }

    private void authorize(HttpExchange ex) throws IOException {
        Map<String, String> q = form(ex.getRequestURI().getRawQuery());
        String redirect = q.get("redirect_uri");
        if (!CLIENT_ID.equals(q.get("client_id")) || redirect == null || !"S256".equals(q.get("code_challenge_method"))) {
            send(ex, 400, "text/plain", "bad authorization request " + q);
            return;
        }
        String back = redirect + (redirect.contains("?") ? "&" : "?") + "state=" + enc(q.get("state"))
                + "&iss=" + enc(issuer);
        if ("cancel".equals(q.get("test_user"))) {
            redirect(ex, back + "&error=access_denied");
            return;
        }
        TestUser user = USERS.stream().filter(u -> u.username().equals(q.get("test_user"))).findFirst().orElse(null);
        if (user == null) {
            send(ex, 400, "text/plain", "unknown test_user");
            return;
        }
        String code = UUID.randomUUID().toString();
        codes.put(code, new Pending(user, q.get("nonce"), redirect, q.get("code_challenge")));
        redirect(ex, back + "&code=" + code);
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> f = form(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        Pending pending = codes.remove(String.valueOf(f.get("code")));
        if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            json(ex, 401, Map.of("error", "invalid_client"));
            return;
        }
        if (pending == null || !pending.redirectUri().equals(f.get("redirect_uri"))
                || !pending.codeChallenge().equals(s256(f.get("code_verifier")))) {
            json(ex, 400, Map.of("error", "invalid_grant"));
            return;
        }
        String accessToken = UUID.randomUUID().toString();
        accessTokens.put(accessToken, pending.user());
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer).subject(subject(pending.user())).audience(CLIENT_ID)
                .issueTime(java.util.Date.from(now)).expirationTime(java.util.Date.from(now.plusSeconds(300)))
                .claim("nonce", pending.nonce()).claim("azp", CLIENT_ID);
        claims(pending.user()).forEach(claims::claim);
        SignedJWT idToken = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims.build());
        try {
            idToken.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        json(ex, 200, Map.of("access_token", accessToken, "token_type", "Bearer", "expires_in", 300,
                "id_token", idToken.serialize(), "scope", "openid profile email"));
    }

    private void userinfo(HttpExchange ex) throws IOException {
        String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
        TestUser user = auth.startsWith("Bearer ") ? accessTokens.get(auth.substring(7)) : null;
        if (user == null) {
            json(ex, 401, Map.of("error", "invalid_token"));
            return;
        }
        Map<String, Object> info = new LinkedHashMap<>(claims(user));
        info.put("sub", subject(user));
        json(ex, 200, info);
    }

    // --- helpers -------------------------------------------------------------------------

    /** Stable and unlike the username, as at real providers. */
    static String subject(TestUser user) {
        return UUID.nameUUIDFromBytes(user.username().getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Map<String, Object> claims(TestUser user) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("preferred_username", user.username());
        claims.put("name", user.name());
        claims.put("given_name", user.firstName());
        claims.put("family_name", user.lastName());
        claims.put("email", user.email());
        claims.put("email_verified", user.emailVerified());
        claims.put("groups", user.groups());
        return claims;
    }

    private static String s256(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(String.valueOf(verifier).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> form(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return values;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            values.put(URLDecoder.decode(name, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String enc(String s) {
        return URLEncoder.encode(String.valueOf(s), StandardCharsets.UTF_8);
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static void json(HttpExchange ex, int status, Map<String, ?> body) throws IOException {
        send(ex, status, "application/json", JSONObjectUtils.toJSONString(new LinkedHashMap<String, Object>(body)));
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
