package org.buggywuggy;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Optional;

/**
 * A minimal browser-like API client for integration tests: keeps cookies (session and
 * XSRF-TOKEN) and sends the CSRF header on state-changing requests, like the web UI will.
 */
public class ApiClient {

    public record Response(int status, String body) {

        public <T> T json(String path) {
            return JsonPath.read(body, path);
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;
    private boolean sendCsrfHeader = true;

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    /** Disables the CSRF header, to test CSRF protection. */
    public ApiClient withoutCsrf() {
        sendCsrfHeader = false;
        return this;
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    public Response post(String path, String json) {
        return send(withBody(path, "POST", json));
    }

    public Response put(String path, String json) {
        return send(withBody(path, "PUT", json));
    }

    public Response patch(String path, String json) {
        return send(withBody(path, "PATCH", json));
    }

    public Response delete(String path) {
        return send(withCsrf(HttpRequest.newBuilder(uri(path)).DELETE()));
    }

    public Response login(String username, String password) {
        return post("/api/v1/auth/login", """
                {"username": "%s", "password": "%s"}""".formatted(username, password));
    }

    public Response logout() {
        return post("/api/v1/auth/logout", null);
    }

    public Optional<String> cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    private HttpRequest.Builder withBody(String path, String method, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
        return withCsrf(builder);
    }

    private HttpRequest.Builder withCsrf(HttpRequest.Builder builder) {
        if (sendCsrfHeader) {
            if (cookie("XSRF-TOKEN").isEmpty()) {
                get("/api/v1/auth/csrf");
            }
            cookie("XSRF-TOKEN").ifPresent(token -> builder.header("X-XSRF-TOKEN", token));
        }
        return builder;
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            var response = http.send(builder.header("Accept", "application/json").build(), BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }
}
