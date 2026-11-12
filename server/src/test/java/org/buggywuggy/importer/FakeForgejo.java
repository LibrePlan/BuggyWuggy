/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Forgejo server for tests: answers the API calls the importer makes with the JSON it is
 * given, in the shape of the real API (checked against codeberg.org). Lists are served on
 * page 1; later pages are empty.
 */
final class FakeForgejo implements AutoCloseable {

    static final String TOKEN = "secret-forgejo-token";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final Map<String, Object> json = new ConcurrentHashMap<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();

    FakeForgejo() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            requests.add(path + (query.isEmpty() ? "" : "?" + query));
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(path + " " + auth);
            byte[] body;
            int status = 200;
            String type = "application/json";
            if (auth != null && !auth.equals("token " + TOKEN)) {
                status = 401;
                body = "{\"message\":\"token is required\"}".getBytes(StandardCharsets.UTF_8);
            } else if (files.containsKey(path)) {
                body = files.get(path);
                type = "image/png";
            } else if (json.containsKey(path)) {
                Object value = json.get(path);
                if (value instanceof List<?> && query.matches(".*\\bpage=([2-9]|\\d\\d+).*")) {
                    value = List.of();
                }
                body = JSON.writeValueAsBytes(value);
                if (value instanceof List<?> list) {
                    exchange.getResponseHeaders().add("X-Total-Count", String.valueOf(list.size()));
                }
            } else {
                status = 404;
                body = "{\"message\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void json(String apiPath, Object value) {
        json.put("/api/v1" + apiPath, value);
    }

    void file(String path, byte[] content) {
        files.put(path, content);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // --- builders in the shape of the Forgejo API ---------------------------------------

    static Map<String, Object> user(long id, String login, String fullName, String email) {
        return map("id", id, "login", login, "full_name", fullName, "email", email, "is_admin", false,
                "username", login);
    }

    static Map<String, Object> label(long id, String name) {
        return map("id", id, "name", name, "color", "e11d21", "description", "", "exclusive", false);
    }

    static Map<String, Object> milestone(long id, String title, String state) {
        return map("id", id, "title", title, "description", "First release", "state", state,
                "due_on", "2026-03-01T00:59:59+01:00", "closed_at", null);
    }

    static Map<String, Object> asset(long id, String name, String uuid, String base) {
        return map("id", id, "name", name, "size", 4, "download_count", 0, "created_at", "2026-01-05T10:00:00+01:00",
                "uuid", uuid, "browser_download_url", base + "/attachments/" + uuid, "type", "attachment");
    }

    static Map<String, Object> issue(String base, long id, long number, Map<String, Object> user, String title,
            String body, String state) {
        return map("id", id, "url", base + "/api/v1/repos/acme/widgets/issues/" + number,
                "html_url", base + "/acme/widgets/issues/" + number, "number", number, "user", user,
                "original_author", "", "original_author_id", 0, "title", title, "body", body, "ref", "",
                "assets", List.of(), "labels", List.of(), "milestone", null, "assignee", null, "assignees", null,
                "state", state, "is_locked", false, "comments", 0, "created_at", "2026-01-05T10:00:00+01:00",
                "updated_at", "2026-01-09T12:00:00+01:00", "closed_at", null, "due_date", null,
                "pull_request", null,
                "repository", map("id", 7, "name", "widgets", "owner", "acme", "full_name", "acme/widgets"),
                "pin_order", 0);
    }

    static Map<String, Object> comment(String base, long id, Map<String, Object> user, String body, String at) {
        return map("id", id, "html_url", base + "/acme/widgets/issues/1#issuecomment-" + id,
                "pull_request_url", "", "issue_url", base + "/acme/widgets/issues/1", "user", user,
                "original_author", "", "original_author_id", 0, "body", body, "assets", List.of(),
                "created_at", at, "updated_at", at);
    }

    static Map<String, Object> event(long id, String type, Map<String, Object> user, String at, Object... fields) {
        Map<String, Object> event = map("id", id, "type", type, "user", user, "body", "", "created_at", at,
                "updated_at", at, "ref_action", "none", "label", null, "assignee", null, "removed_assignee", false,
                "milestone", null, "old_milestone", null, "old_title", "", "new_title", "", "ref_issue", null,
                "dependent_issue", null, "ref_commit_sha", "");
        event.putAll(map(fields));
        return event;
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
