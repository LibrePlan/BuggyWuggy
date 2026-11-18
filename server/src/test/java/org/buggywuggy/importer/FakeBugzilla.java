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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Bugzilla for tests, under the path /bugzilla, with its REST API under /rest.cgi (as on a
 * web server that does not rewrite /rest) in the shape of the real one. Bug searches are paged
 * two at a time, whatever limit is asked for (as max_search_results could). It accepts the API
 * key in X-BUGZILLA-API-KEY, or no key at all.
 */
final class FakeBugzilla implements AutoCloseable {

    static final String KEY = "xKeyOfBugzilla0123456789abcdefghijklmnop";
    static final String PATH = "/bugzilla";
    static final String REST = "/rest.cgi";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final Map<String, Object> json = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> bugs = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> products = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> users = new CopyOnWriteArrayList<>();
    final List<String> methods = new CopyOnWriteArrayList<>();
    /** Path, query and the API key header of each request. */
    final List<String> requests = new CopyOnWriteArrayList<>();
    /** Headers with credentials other than the API key: none may arrive. */
    final List<String> otherCredentials = new CopyOnWriteArrayList<>();

    FakeBugzilla() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String full = exchange.getRequestURI().getRawPath();
            String raw = exchange.getRequestURI().getRawQuery();
            Map<String, List<String>> query = query(raw);
            methods.add(exchange.getRequestMethod());
            String key = exchange.getRequestHeaders().getFirst("X-BUGZILLA-API-KEY");
            requests.add(full + " " + (raw == null ? "" : URLDecoder.decode(raw, StandardCharsets.UTF_8)) + " " + key);
            for (String header : List.of("Authorization", "X-BUGZILLA-LOGIN", "X-BUGZILLA-PASSWORD")) {
                if (exchange.getRequestHeaders().getFirst(header) != null) {
                    otherCredentials.add(header);
                }
            }
            if (key != null && !key.equals(KEY)) {
                send(exchange, 401, Map.of("error", true, "code", 306, "message", "The API key is not valid."));
                return;
            }
            if (!full.startsWith(PATH + REST + "/")) {
                byte[] html = "<html>Not the API</html>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(404, html.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(html);
                }
                exchange.close();
                return;
            }
            String path = full.substring((PATH + REST).length());
            if (path.equals("/bug")) {
                String product = first(query, "product");
                int offset = Integer.parseInt(first(query, "offset") == null ? "0" : first(query, "offset"));
                List<Map<String, Object>> found = bugs.stream()
                        .filter(b -> product == null || product.equals(b.get("product"))).toList();
                send(exchange, 200, Map.of("bugs", found.subList(Math.min(offset, found.size()),
                        Math.min(offset + 2, found.size()))));
            } else if (path.equals("/product")) {
                List<String> names = query.getOrDefault("names", List.of());
                List<Map<String, Object>> found = products.stream()
                        .filter(p -> names.isEmpty() || names.contains(p.get("name"))).toList();
                send(exchange, 200, Map.of("products", found));
            } else if (path.equals("/user")) {
                List<String> names = query.getOrDefault("names", List.of());
                send(exchange, 200, Map.of("users", users.stream()
                        .filter(u -> names.isEmpty() || names.contains(u.get("name"))).toList()));
            } else if (path.equals("/whoami") && key == null) {
                send(exchange, 401, Map.of("error", true, "code", 410, "message", "You must log in."));
            } else if (json.containsKey(path)) {
                send(exchange, 200, json.get(path));
            } else {
                send(exchange, 404, Map.of("error", true, "code", 101, "message", "Not found"));
            }
        });
        server.start();
    }

    private static String first(Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static Map<String, List<String>> query(String raw) {
        Map<String, List<String>> query = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.computeIfAbsent(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            k -> new ArrayList<>()).add(URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return query;
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        exchange.close();
    }

    /** The address of Bugzilla, with its path. */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
    }

    /** A response of the API, by path after /rest.cgi. */
    void json(String path, Object value) {
        json.put(path, value);
    }

    void bug(Map<String, Object> bug) {
        bugs.add(bug);
    }

    void product(Map<String, Object> product) {
        products.add(product);
    }

    /** An account, as /user shows it to the importer (without e-mail address: not an administrator). */
    void user(String login, String realName) {
        users.add(map("id", users.size() + 1, "name", login, "real_name", realName));
    }

    @Override
    public void close() {
        server.stop(0);
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
