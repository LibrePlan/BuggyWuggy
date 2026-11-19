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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * A GitLab server for tests, with its API under /api/v4, in the shape of the real API
 * (checked against gitlab.com). Paths are matched raw, so a project path is
 * {@code /projects/acme%2Ftools%2Fwidgets}. Lists can have several pages (x-next-page).
 * Requests without a token are allowed, as for public projects.
 */
final class FakeGitLab implements AutoCloseable {

    static final String TOKEN = "glpat-secret-gitlab-token";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final Map<String, List<List<Object>>> pages = new ConcurrentHashMap<>();
    private final Map<String, Object> json = new ConcurrentHashMap<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Set<String> rateLimited = ConcurrentHashMap.newKeySet();
    /** API paths only for logged-in users, as labels and comments on gitlab.com. */
    final Set<String> loginOnly = ConcurrentHashMap.newKeySet();
    final List<String> methods = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();

    FakeGitLab() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
            methods.add(exchange.getRequestMethod());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(path + " " + auth);
            if (auth != null && !auth.equals("Bearer " + TOKEN)) {
                send(exchange, 401, "application/json", "{\"message\":\"401 Unauthorized\"}".getBytes());
                return;
            }
            if (files.containsKey(path)) {
                send(exchange, 200, "image/png", files.get(path));
                return;
            }
            String api = path.startsWith("/api/v4") ? path.substring("/api/v4".length()) : path;
            if (auth == null && loginOnly.contains(api)) {
                send(exchange, 401, "application/json", "{\"message\":\"401 Unauthorized\"}".getBytes());
                return;
            }
            if (rateLimited.remove(api)) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                send(exchange, 429, "text/plain", "Retry later".getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.getResponseHeaders().add("RateLimit-Remaining", "1900");
            if (pages.containsKey(api)) {
                List<List<Object>> all = pages.get(api);
                int page = query.matches(".*\\bpage=\\d+.*") ? Integer.parseInt(query.replaceAll(
                        ".*\\bpage=(\\d+).*", "$1")) : 1;
                List<Object> items = page <= all.size() ? all.get(page - 1) : List.of();
                exchange.getResponseHeaders().add("x-next-page", page < all.size() ? String.valueOf(page + 1) : "");
                send(exchange, 200, "application/json", JSON.writeValueAsBytes(items));
            } else if (json.containsKey(api)) {
                send(exchange, 200, "application/json", JSON.writeValueAsBytes(json.get(api)));
            } else {
                send(exchange, 404, "application/json", "{\"message\":\"404 Not found\"}".getBytes());
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

    void json(String apiPath, Object value) {
        json.put(apiPath, value);
    }

    @SafeVarargs
    final void pages(String apiPath, List<Object>... lists) {
        pages.put(apiPath, List.of(lists));
    }

    /** A file at a path of the web server (not the API). */
    void file(String path, byte[] content) {
        files.put(path, content);
    }

    void rateLimitOnce(String apiPath) {
        rateLimited.add(apiPath);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static Map<String, Object> user(long id, String username, String name) {
        return map("id", id, "username", username, "name", name, "state", "active",
                "web_url", "https://gitlab.example/" + username);
    }

    Map<String, Object> issue(long id, long iid, Map<String, Object> author, String title, String description,
            String state) {
        return map("id", id, "iid", iid, "project_id", 7, "title", title, "description", description, "state", state,
                "created_at", "2026-01-05T09:00:00.000Z", "updated_at", "2026-01-09T11:00:00.000Z",
                "closed_at", null, "labels", list(), "milestone", null, "assignees", list(), "author", author,
                "user_notes_count", 0, "due_date", null, "web_url", url() + "/acme/tools/widgets/-/issues/" + iid,
                "confidential", false, "issue_type", "issue", "type", "ISSUE",
                "references", map("short", "#" + iid, "full", "acme/tools/widgets#" + iid));
    }

    static Map<String, Object> note(long id, Map<String, Object> author, String body, boolean system, String at) {
        return map("id", id, "body", body, "author", author, "created_at", at, "updated_at", at, "system", system,
                "internal", false, "confidential", false, "noteable_type", "Issue");
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
