package org.buggywuggy;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
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

    /** A file for {@link #upload}. */
    public record FilePart(String fileName, String contentType, byte[] content) {
    }

    /** A binary response, e.g. a downloaded attachment. */
    public record Download(int status, java.net.http.HttpHeaders headers, byte[] body) {

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    /** Sends files as {@code multipart/form-data}, each in a part named {@code file}. */
    public Response upload(String path, List<FilePart> files) {
        String boundary = "----BuggyWuggyTest" + System.nanoTime();
        var body = new java.io.ByteArrayOutputStream();
        for (FilePart file : files) {
            body.writeBytes(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + file.fileName() + "\"\r\n"
                    + "Content-Type: " + file.contentType() + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            body.writeBytes(file.content());
            body.writeBytes("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .POST(BodyPublishers.ofByteArray(body.toByteArray()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        return send(withCsrf(builder));
    }

    public Download download(String path) {
        try {
            var response = http.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return new Download(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A response that is not followed, e.g. a redirect: its status and Location header. */
    public record Redirect(int status, String location) {
    }

    /**
     * GETs a path (or a full URL) without following a redirect. A Location on this server is
     * returned as path, e.g. {@code /login?sso-error=sso.no-account}.
     */
    public Redirect redirect(String pathOrUrl) {
        URI target = pathOrUrl.startsWith("http") ? URI.create(pathOrUrl) : uri(pathOrUrl);
        try {
            var response = http.send(HttpRequest.newBuilder(target).GET().build(), HttpResponse.BodyHandlers.discarding());
            String location = response.headers().firstValue("Location").orElse(null);
            if (location != null && location.startsWith(baseUrl)) {
                location = location.substring(baseUrl.length());
            }
            return new Redirect(response.statusCode(), location);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
