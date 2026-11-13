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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Redmine for tests, under the path /redmine, with its REST API in the shape of the real
 * one: lists paged with offset, limit and total_count. It accepts an API key as username of
 * basic authentication, or no authentication at all.
 */
final class FakeRedmine implements AutoCloseable {

    static final String KEY = "0123456789abcdef0123456789abcdef01234567";
    static final String BASIC = "Basic " + Base64.getEncoder().encodeToString((KEY + ":x")
            .getBytes(StandardCharsets.UTF_8));
    static final String PATH = "/redmine";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Paged(String field, List<Object> items) {
    }

    private final HttpServer server;
    private final Map<String, Object> json = new ConcurrentHashMap<>();
    private final Map<String, Paged> paged = new ConcurrentHashMap<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final List<String> methods = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    final List<String> queries = new CopyOnWriteArrayList<>();

    FakeRedmine() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String full = exchange.getRequestURI().getRawPath();
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            methods.add(exchange.getRequestMethod());
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(full + " " + auth);
            queries.add(full + " " + query);
            if (auth != null && !auth.equals(BASIC)) {
                send(exchange, 401, "application/json", "{\"errors\":[\"Unauthorized\"]}".getBytes());
                return;
            }
            if (!full.startsWith(PATH + "/")) {
                send(exchange, 404, "text/html", "<html>Not Redmine</html>".getBytes());
                return;
            }
            String path = full.substring(PATH.length());
            if (files.containsKey(path)) {
                send(exchange, 200, "image/png", files.get(path));
            } else if (paged.containsKey(path)) {
                Paged p = paged.get(path);
                int offset = Integer.parseInt(query.getOrDefault("offset", "0"));
                int limit = Math.min(2, Integer.parseInt(query.getOrDefault("limit", "25")));
                Map<String, Object> page = new LinkedHashMap<>();
                page.put(p.field(), p.items().subList(Math.min(offset, p.items().size()),
                        Math.min(offset + limit, p.items().size())));
                page.put("total_count", p.items().size());
                page.put("offset", offset);
                page.put("limit", limit);
                send(exchange, 200, "application/json", JSON.writeValueAsBytes(page));
            } else if (json.containsKey(path)) {
                send(exchange, 200, "application/json", JSON.writeValueAsBytes(json.get(path)));
            } else {
                send(exchange, 404, "application/json", "{\"errors\":[\"Not found\"]}".getBytes());
            }
        });
        server.start();
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

    /** The address of Redmine, with its path. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
    }

    void json(String path, Object value) {
        json.put(path, value);
    }

    /** A list paged two at a time, in {@code field} (issues, projects). */
    void pages(String path, String field, List<Object> items) {
        paged.put(path, new Paged(field, items));
    }

    void file(String path, byte[] content) {
        files.put(path, content);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static Map<String, Object> named(long id, String name) {
        return map("id", id, "name", name);
    }

    static Map<String, Object> issue(long id, long projectId, String tracker, long statusId, String subject,
            Map<String, Object> author) {
        return map("id", id, "project", named(projectId, "Project " + projectId), "tracker", named(1, tracker),
                "status", named(statusId, "Status " + statusId), "priority", named(2, "Normal"), "author", author,
                "subject", subject, "description", "", "is_private", false,
                "created_on", "2026-01-05T09:00:00Z", "updated_on", "2026-01-09T11:00:00Z", "closed_on", null,
                "attachments", list(), "relations", list());
    }

    static Map<String, Object> relation(long id, long from, long to, String type) {
        return map("id", id, "issue_id", from, "issue_to_id", to, "relation_type", type, "delay", null);
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
