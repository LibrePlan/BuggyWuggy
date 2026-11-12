/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * A GitHub Enterprise server for tests, at 127.0.0.1 with its API under /api/v3, in the shape
 * of the real API (checked against api.github.com). Lists can have several pages, linked
 * with {@code Link: rel="next"}. Attachments redirect to a second server, "storage", at
 * localhost: a different host, which must never get the token.
 */
final class FakeGitHub implements AutoCloseable {

    static final String TOKEN = "ghp_secret-github-token";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final HttpServer storage;
    private final Map<String, List<List<Object>>> pages = new ConcurrentHashMap<>();
    private final Map<String, Object> json = new ConcurrentHashMap<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    /** Paths answered once with "rate limit used up", resetting a second later. */
    private final Set<String> rateLimited = ConcurrentHashMap.newKeySet();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> methods = new CopyOnWriteArrayList<>();
    final List<String> storageAuthorizations = new CopyOnWriteArrayList<>();

    FakeGitHub() throws IOException {
        storage = HttpServer.create(new InetSocketAddress(0), 0); // all addresses: "localhost" may be ::1
        storage.createContext("/", exchange -> {
            methods.add(exchange.getRequestMethod());
            storageAuthorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body = files.get(exchange.getRequestURI().getPath());
            if (body == null) {
                send(exchange, 404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8));
            } else {
                send(exchange, 200, "image/png", body);
            }
        });
        storage.start();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            requests.add(path + (query.isEmpty() ? "" : "?" + query));
            methods.add(exchange.getRequestMethod());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (path.startsWith("/user-attachments/")) {
                // Like github.com: a redirect to a temporary address of the file elsewhere.
                exchange.getResponseHeaders().add("Location", storageUrl() + "/files" + path.substring(
                        path.lastIndexOf('/')) + ".png?signature=x");
                send(exchange, 302, "text/plain", new byte[0]);
                return;
            }
            if (!("Bearer " + TOKEN).equals(auth)) {
                send(exchange, 401, "application/json", "{\"message\":\"Bad credentials\"}".getBytes());
                return;
            }
            String api = path.startsWith("/api/v3") ? path.substring("/api/v3".length()) : path;
            if (rateLimited.remove(api)) {
                exchange.getResponseHeaders().add("x-ratelimit-remaining", "0");
                exchange.getResponseHeaders().add("x-ratelimit-reset",
                        String.valueOf(Instant.now().getEpochSecond() + 1));
                send(exchange, 403, "application/json",
                        "{\"message\":\"API rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.getResponseHeaders().add("x-ratelimit-remaining", "4000");
            if (pages.containsKey(api)) {
                List<List<Object>> all = pages.get(api);
                int page = query.matches(".*\\bpage=\\d+.*") ? Integer.parseInt(query.replaceAll(
                        ".*\\bpage=(\\d+).*", "$1")) : 1;
                List<Object> items = page <= all.size() ? all.get(page - 1) : List.of();
                if (page < all.size()) {
                    exchange.getResponseHeaders().add("Link", "<" + url() + path + "?per_page=100&page="
                            + (page + 1) + ">; rel=\"next\"");
                }
                send(exchange, 200, "application/json", JSON.writeValueAsBytes(items));
            } else if (json.containsKey(api)) {
                send(exchange, 200, "application/json", JSON.writeValueAsBytes(json.get(api)));
            } else {
                send(exchange, 404, "application/json", "{\"message\":\"Not Found\"}".getBytes());
            }
        });
        server.start();
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** At "localhost", so another host name than the API. */
    String storageUrl() {
        return "http://localhost:" + storage.getAddress().getPort();
    }

    void json(String apiPath, Object value) {
        json.put(apiPath, value);
    }

    @SafeVarargs
    final void pages(String apiPath, List<Object>... lists) {
        pages.put(apiPath, List.of(lists));
    }

    /** The file an attachment link {@code /user-attachments/assets/<id>} redirects to. */
    void attachment(String id, byte[] content) {
        files.put("/files/" + id + ".png", content);
    }

    void rateLimitOnce(String apiPath) {
        rateLimited.add(apiPath);
    }

    @Override
    public void close() {
        server.stop(0);
        storage.stop(0);
    }

    // --- builders in the shape of the GitHub API ---------------------------------------

    static Map<String, Object> user(long id, String login) {
        return map("login", login, "id", id, "type", "User", "site_admin", false,
                "html_url", "https://github.example/" + login);
    }

    Map<String, Object> issue(long id, long number, Map<String, Object> user, String title, String body,
            String state) {
        return map("id", id, "number", number, "html_url", url() + "/acme/widgets/issues/" + number,
                "repository_url", url() + "/api/v3/repos/acme/widgets", "user", user, "title", title, "body", body,
                "labels", list(), "milestone", null, "assignee", null, "assignees", list(), "state", state,
                "state_reason", null, "comments", 0, "created_at", "2026-01-05T09:00:00Z",
                "updated_at", "2026-01-09T11:00:00Z", "closed_at", null, "type", null,
                "sub_issues_summary", map("total", 0, "completed", 0, "percent_completed", 0),
                "issue_dependencies_summary", map("blocked_by", 0, "total_blocked_by", 0, "blocking", 0,
                        "total_blocking", 0));
    }

    static Map<String, Object> event(String event, Map<String, Object> actor, String at, Object... fields) {
        Map<String, Object> e = map("event", event, "actor", actor, "created_at", at);
        e.putAll(map(fields));
        return e;
    }

    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    static List<Object> list(Object... items) {
        return new ArrayList<>(List.of(items));
    }
}
