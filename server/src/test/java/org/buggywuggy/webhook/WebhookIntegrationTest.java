/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.buggywuggy.ApiClient;
import org.buggywuggy.issue.IssueTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Outgoing webhooks: what is posted, signed, in which order, and what happens when it fails. */
class WebhookIntegrationTest extends IssueTestSupport {

    private record Received(Map<String, String> headers, String body) {
        JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }
    }

    @Autowired
    private WebhookSender sender;

    private HttpServer receiver;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger answer = new AtomicInteger(204);

    @BeforeEach
    void startReceiver() throws Exception {
        jdbc.update("delete from webhook");
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
            received.add(new Received(headers, body));
            exchange.sendResponseHeaders(answer.get(), -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
        jdbc.update("delete from webhook");
    }

    private String url() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    private ApiClient.Response createWebhook(ApiClient admin, String events, String projects) {
        return admin.post("/api/v1/admin/webhooks", """
                {"name": "Desk", "url": "%s", "events": %s, %s, "active": true}"""
                .formatted(url(), events, projects));
    }

    private static String code(ApiClient.Response response) {
        return response.json("$.code");
    }

    @Test
    void postsTheEventsOfItsProjectsInOrderAndSigned() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "WH", "Webhooks");
        createProject(admin, "OTHER", "Other");
        var created = createWebhook(admin, """
                ["issue.created", "issue.changed", "issue.commented", "issue.deleted"]""", """
                "allProjects": false, "projectKeys": ["WH"]""");
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String secret = created.json("$.secret");
        assertThat(secret).startsWith(WebhookService.SECRET_PREFIX);
        assertThat(created.<List<String>>json("$.webhook.projectKeys")).containsExactly("WH");
        // Stored encrypted only.
        assertThat(jdbc.queryForObject("select position(convert_to(?, 'UTF8') in secret_encrypted) from webhook",
                Integer.class, secret)).isZero();

        String key = createIssue(admin, "WH", "Bug", "Payment received");
        createIssue(admin, "OTHER", "Bug", "Not for the webhook");
        int version = admin.get("/api/v1/issues/" + key).json("$.version");
        assertThat(admin.patch("/api/v1/issues/" + key, """
                {"version": %d, "summary": "Payment received <EUR>"}""".formatted(version)).status()).isEqualTo(200);
        assertThat(admin.post("/api/v1/issues/" + key + "/comments", """
                {"body": "Code sent."}""").status()).isEqualTo(201);

        sender.sendPending();
        assertThat(received).extracting(r -> r.headers().get("x-buggywuggy-event"))
                .containsExactly("issue.created", "issue.changed", "issue.commented");
        for (Received r : received) {
            assertThat(r.headers().get("x-buggywuggy-signature")).isEqualTo(
                    "sha256=" + WebhookSender.sign(secret, r.body().getBytes(StandardCharsets.UTF_8)));
            assertThat(r.headers().get("content-type")).startsWith("application/json");
            assertThat(r.headers().get("x-buggywuggy-delivery")).isNotBlank();
        }
        JsonNode first = received.get(0).json();
        assertThat(first.path("format").asString()).isEqualTo(WebhookService.FORMAT);
        assertThat(first.path("installation").asString()).isEqualTo("http://bugs.test");
        assertThat(first.path("actor").path("username").asString()).isEqualTo(ADMIN);
        assertThat(first.path("issue").path("key").asString()).isEqualTo(key);
        assertThat(first.path("issue").path("url").asString()).isEqualTo("http://bugs.test/browse/" + key);
        assertThat(first.path("issue").path("project").path("key").asString()).isEqualTo("WH");
        JsonNode changed = received.get(1).json();
        assertThat(changed.path("issue").path("summary").asString()).isEqualTo("Payment received <EUR>");
        assertThat(changed.path("changes").toString()).contains("summary", "Payment received <EUR>");
        JsonNode commented = received.get(2).json();
        assertThat(commented.path("comment").path("body").asString()).isEqualTo("Code sent.");
        assertThat(commented.path("comment").path("external").asBoolean()).isFalse();

        // Deleted: what it was, read before it was gone.
        assertThat(admin.delete("/api/v1/issues/" + key).status()).isEqualTo(204);
        sender.sendPending();
        assertThat(received).hasSize(4);
        JsonNode deleted = received.get(3).json();
        assertThat(deleted.path("event").asString()).isEqualTo("issue.deleted");
        assertThat(deleted.path("issue").path("key").asString()).isEqualTo(key);
        assertThat(deleted.path("issue").path("summary").asString()).isEqualTo("Payment received <EUR>");

        var deliveries = admin.get("/api/v1/admin/webhooks/" + created.json("$.webhook.id") + "/deliveries");
        assertThat(deliveries.<List<String>>json("$[*].status")).containsOnly("SENT");
        assertThat(admin.get("/api/v1/admin/webhooks").<String>json("$[0].lastDelivery.status")).isEqualTo("SENT");
    }

    @Test
    void retriesInOrderGivesUpAndCanBeTriedAgain() {
        ApiClient admin = setupAndLoginAdmin();
        var created = createWebhook(admin, "[\"issue.created\"]", "\"allProjects\": true");
        long id = ((Number) created.json("$.webhook.id")).longValue();

        answer.set(500);
        long ping = ((Number) admin.post("/api/v1/admin/webhooks/" + id + "/test", null).json("$.id")).longValue();
        sender.sendPending();
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().json().path("event").asString()).isEqualTo("ping");
        Map<String, Object> row = jdbc.queryForMap("select * from webhook_delivery where id = ?", ping);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("response_status")).isEqualTo(500);
        assertThat(row.get("last_error")).isEqualTo("HTTP 500");

        // A later event waits for the one before it: the order stays.
        admin.post("/api/v1/admin/webhooks/" + id + "/test", null);
        sender.sendPending();
        assertThat(received).hasSize(1);

        // The last attempt fails: given up, and the next one goes.
        answer.set(200);
        jdbc.update("update webhook_delivery set attempts = 9, next_attempt_at = now() where id = ?", ping);
        answer.set(503);
        sender.sendPending();
        assertThat(jdbc.queryForObject("select status from webhook_delivery where id = ?", String.class, ping))
                .isEqualTo("FAILED");
        answer.set(200);
        sender.sendPending();
        assertThat(received).hasSize(3);
        assertThat(admin.get("/api/v1/admin/webhooks/" + id).<Number>json("$.failed").intValue()).isEqualTo(1);

        var retried = admin.post("/api/v1/admin/webhooks/" + id + "/deliveries/" + ping + "/retry", null);
        assertThat(retried.status()).as(retried.toString()).isEqualTo(200);
        assertThat(retried.<String>json("$.status")).isEqualTo("PENDING");
        sender.sendPending();
        assertThat(received).hasSize(4);
        assertThat(jdbc.queryForObject("select status from webhook_delivery where id = ?", String.class, ping))
                .isEqualTo("SENT");
        assertThat(admin.post("/api/v1/admin/webhooks/" + id + "/deliveries/" + ping + "/retry", null).status())
                .isEqualTo(404);
    }

    @Test
    void checksItsSettingsAndIsForAdministratorsOnly() {
        ApiClient admin = setupAndLoginAdmin();
        createProject(admin, "WH", "Webhooks");
        createUser(admin, "otto", "otto-password-12");

        var badUrl = admin.post("/api/v1/admin/webhooks", """
                {"name": "x", "url": "ftp://example.org/x", "events": ["issue.created"], "allProjects": true,
                 "active": true}""");
        assertThat(badUrl.status()).isEqualTo(400);
        assertThat(code(badUrl)).isEqualTo("webhook.bad-url");
        assertThat(code(admin.post("/api/v1/admin/webhooks", """
                {"name": "x", "url": "https://user:pw@example.org/x", "events": ["issue.created"],
                 "allProjects": true, "active": true}"""))).isEqualTo("webhook.bad-url");
        assertThat(code(createWebhook(admin, "[\"issue.exploded\"]", "\"allProjects\": true")))
                .isEqualTo("webhook.bad-event");
        assertThat(code(createWebhook(admin, "[\"issue.created\"]", "\"allProjects\": false, \"projectKeys\": []")))
                .isEqualTo("webhook.no-projects");
        assertThat(code(createWebhook(admin, "[\"issue.created\"]",
                "\"allProjects\": false, \"projectKeys\": [\"NOPE\"]"))).isEqualTo("webhook.unknown-project");
        assertThat(loginAs("otto", "otto-password-12").get("/api/v1/admin/webhooks").status()).isEqualTo(403);

        var created = createWebhook(admin, "[\"issue.created\"]", "\"allProjects\": true");
        long id = ((Number) created.json("$.webhook.id")).longValue();
        String secret = created.json("$.secret");

        // Inactive: nothing is queued.
        var off = admin.put("/api/v1/admin/webhooks/" + id, """
                {"name": "Desk", "url": "%s", "events": ["issue.created"], "allProjects": true, "active": false}"""
                .formatted(url()));
        assertThat(off.status()).as(off.toString()).isEqualTo(200);
        createIssue(admin, "WH", "Bug", "Quiet");
        assertThat(jdbc.queryForObject("select count(*) from webhook_delivery", Integer.class)).isZero();

        // A new secret signs from now on.
        String rotated = admin.post("/api/v1/admin/webhooks/" + id + "/secret", null).json("$.secret");
        assertThat(rotated).isNotEqualTo(secret).startsWith(WebhookService.SECRET_PREFIX);
        admin.put("/api/v1/admin/webhooks/" + id, """
                {"name": "Desk", "url": "%s", "events": ["issue.created"], "allProjects": true, "active": true}"""
                .formatted(url()));
        admin.post("/api/v1/admin/webhooks/" + id + "/test", null);
        sender.sendPending();
        Received ping = received.getFirst();
        assertThat(ping.headers().get("x-buggywuggy-signature"))
                .isEqualTo("sha256=" + WebhookSender.sign(rotated, ping.body().getBytes(StandardCharsets.UTF_8)));

        assertThat(admin.delete("/api/v1/admin/webhooks/" + id).status()).isEqualTo(204);
        assertThat(admin.get("/api/v1/admin/webhooks/" + id).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from webhook_delivery", Integer.class)).isZero();
    }
}
