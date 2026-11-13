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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Trac environment at /proj for tests, with the XML-RPC interface of the TracXMLRPC plugin
 * at /proj/rpc (anonymous) and /proj/login/rpc (basic authentication), and attachments under
 * /proj/raw-attachment.
 */
final class FakeTrac implements AutoCloseable {

    static final String USER = "importer";
    static final String PASSWORD = "trac-secret-password";
    static final String BASIC = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD)
            .getBytes(StandardCharsets.UTF_8));
    static final String PATH = "/proj";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    private static final Pattern METHOD = Pattern.compile("<methodName>([^<]*)</methodName>");
    private static final Pattern PARAM = Pattern.compile("<param><value><(string|int)>([^<]*)</\\1></value></param>");

    private final HttpServer server;
    private final Map<String, Function<List<Object>, Object>> methods = new ConcurrentHashMap<>();
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> calls = new CopyOnWriteArrayList<>();

    FakeTrac() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            requests.add(exchange.getRequestMethod() + " " + path + " " + auth);
            if (auth != null && !auth.equals(BASIC)) {
                send(exchange, 401, "text/plain", "Unauthorized".getBytes());
                return;
            }
            if (files.containsKey(path) && exchange.getRequestMethod().equals("GET")) {
                send(exchange, 200, "image/png", files.get(path));
                return;
            }
            boolean rpc = path.equals(PATH + "/rpc") || path.equals(PATH + "/login/rpc");
            if (!rpc || !exchange.getRequestMethod().equals("POST")) {
                send(exchange, 404, "text/html", "<html>Not found</html>".getBytes());
                return;
            }
            if (path.equals(PATH + "/login/rpc") && auth == null) {
                send(exchange, 401, "text/plain", "Login required".getBytes());
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher method = METHOD.matcher(body);
            String name = method.find() ? method.group(1) : "";
            List<Object> params = new ArrayList<>();
            Matcher param = PARAM.matcher(body);
            while (param.find()) {
                String value = param.group(2).replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
                params.add(param.group(1).equals("int") ? Integer.valueOf(value) : value);
            }
            calls.add(name + params);
            Function<List<Object>, Object> handler = methods.get(name);
            String xml = handler == null
                    ? "<methodResponse><fault><value><struct><member><name>faultCode</name><value><int>1</int>"
                            + "</value></member><member><name>faultString</name><value><string>RPC method \""
                            + name + "\" not found</string></value></member></struct></value></fault></methodResponse>"
                    : "<methodResponse><params><param>" + value(handler.apply(params))
                            + "</param></params></methodResponse>";
            send(exchange, 200, "text/xml", ("<?xml version='1.0'?>\n" + xml).getBytes(StandardCharsets.UTF_8));
        });
        server.start();
    }

    private static String value(Object v) {
        return "<value>" + switch (v) {
            case null -> "<nil/>";
            case String s -> "<string>" + s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    + "</string>";
            case Integer i -> "<int>" + i + "</int>";
            case Boolean b -> "<boolean>" + (b ? 1 : 0) + "</boolean>";
            case Instant t -> "<dateTime.iso8601>" + TIME.format(t) + "</dateTime.iso8601>";
            case List<?> list -> "<array><data>" + String.join("", list.stream().map(FakeTrac::value).toList())
                    + "</data></array>";
            case Map<?, ?> map -> "<struct>" + String.join("", map.entrySet().stream().map(e -> "<member><name>"
                    + e.getKey() + "</name>" + value(e.getValue()) + "</member>").toList()) + "</struct>";
            default -> throw new IllegalArgumentException(v.getClass().toString());
        } + "</value>";
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
        return "http://127.0.0.1:" + server.getAddress().getPort() + PATH;
    }

    void method(String name, Function<List<Object>, Object> handler) {
        methods.put(name, handler);
    }

    void file(String path, byte[] content) {
        files.put(path, content);
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
        return new ArrayList<>(java.util.Arrays.asList(items));
    }
}
