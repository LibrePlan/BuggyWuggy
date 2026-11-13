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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Jira server for tests, with REST API v2 under /rest/api/2, in the shape of the real API.
 * Paged lists come in two styles: Server ({@code startAt}, {@code total}) and Cloud
 * ({@code nextPageToken}, {@code isLast}). It accepts a personal access token, e-mail and
 * API token (basic), or no authentication at all.
 */
final class FakeJira implements AutoCloseable {

    static final String TOKEN = "jira-secret-personal-access-token";
    static final String EMAIL = "importer@example.org";
    static final String BASIC = "Basic " + Base64.getEncoder().encodeToString((EMAIL + ":" + TOKEN)
            .getBytes(StandardCharsets.UTF_8));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Paged(String field, boolean cloud, List<List<Object>> pages) {
    }

    private final HttpServer server;
    private final Map<String, Object> json = new ConcurrentHashMap<>();
    private final Map<String, Paged> paged = new ConcurrentHashMap<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Set<String> rateLimited = ConcurrentHashMap.newKeySet();
    final List<String> methods = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    final List<String> queries = new CopyOnWriteArrayList<>();

    FakeJira() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            methods.add(exchange.getRequestMethod());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(path + " " + auth);
            queries.add(path + " " + query);
            if (auth != null && !auth.equals("Bearer " + TOKEN) && !auth.equals(BASIC)) {
                send(exchange, 401, "application/json", "{\"errorMessages\":[\"Unauthorized\"]}".getBytes());
                return;
            }
            if (files.containsKey(path)) {
                send(exchange, 200, "image/png", files.get(path));
                return;
            }
            String api = path.startsWith("/rest/api/2") ? path.substring("/rest/api/2".length()) : path;
            if (rateLimited.remove(api)) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                send(exchange, 429, "text/plain", "Rate limit exceeded".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (paged.containsKey(api)) {
                send(exchange, 200, "application/json", JSON.writeValueAsBytes(page(paged.get(api), query)));
            } else if (json.containsKey(api)) {
                send(exchange, 200, "application/json", JSON.writeValueAsBytes(json.get(api)));
            } else {
                send(exchange, 404, "application/json", "{\"errorMessages\":[\"Not found\"]}".getBytes());
            }
        });
        server.start();
    }

    private static Map<String, Object> page(Paged p, Map<String, String> query) {
        int index = 0;
        if (p.cloud()) {
            index = query.containsKey("nextPageToken") ? Integer.parseInt(query.get("nextPageToken")) : 0;
        } else {
            int startAt = Integer.parseInt(query.getOrDefault("startAt", "0"));
            for (int seen = 0; index < p.pages().size() && seen < startAt; index++) {
                seen += p.pages().get(index).size();
            }
        }
        List<Object> items = index < p.pages().size() ? p.pages().get(index) : List.of();
        Map<String, Object> result = new LinkedHashMap<>();
        if (p.cloud()) {
            boolean last = index >= p.pages().size() - 1;
            result.put(p.field(), items);
            result.put("isLast", last);
            if (!last) {
                result.put("nextPageToken", String.valueOf(index + 1));
            }
        } else {
            int start = 0;
            for (int i = 0; i < index && i < p.pages().size(); i++) {
                start += p.pages().get(i).size();
            }
            result.put("startAt", start);
            result.put("maxResults", 100);
            result.put("total", p.pages().stream().mapToInt(List::size).sum());
            result.put(p.field(), items);
        }
        return result;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return query;
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

    /** A list paged the Server way, in {@code field} (issues, comments, values). */
    @SafeVarargs
    final void pages(String apiPath, String field, List<Object>... lists) {
        paged.put(apiPath, new Paged(field, false, List.of(lists)));
    }

    /** A list paged the Cloud way (nextPageToken). */
    @SafeVarargs
    final void cloudPages(String apiPath, String field, List<Object>... lists) {
        paged.put(apiPath, new Paged(field, true, List.of(lists)));
    }

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

    /** A user of Jira Server: key, username, display name, e-mail address. */
    static Map<String, Object> user(String key, String name, String displayName, String email) {
        return map("key", key, "name", name, "displayName", displayName, "emailAddress", email, "active", true);
    }

    /** An issue as /search returns it. */
    static Map<String, Object> issue(String id, String key, Map<String, Object> fields) {
        return map("id", id, "key", key, "self", "http://jira/rest/api/2/issue/" + id, "fields", fields);
    }

    static Map<String, Object> fields(String summary, String type, Map<String, Object> reporter, String status,
            String category) {
        return map("summary", summary, "description", null, "issuetype", map("name", type, "subtask",
                "Sub-task".equals(type)), "priority", map("id", "3", "name", "Medium"), "status", map("id", status,
                "name", status, "statusCategory", map("key", category)), "resolution", null, "resolutiondate", null,
                "labels", list(), "components", list(), "fixVersions", list(), "assignee", null, "reporter", reporter,
                "creator", reporter, "created", "2026-01-05T09:00:00.000+0000",
                "updated", "2026-01-09T11:00:00.000+0000", "duedate", null,
                "comment", map("startAt", 0, "maxResults", 0, "total", 0, "comments", list()),
                "attachment", list(), "issuelinks", list());
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
