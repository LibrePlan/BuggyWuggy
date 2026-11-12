/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.forgejo;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportRunner.CancelledException;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * A small client for the Forgejo REST API ({@code /api/v1}), which Gitea shares. It pages
 * through lists, waits when the server's rate limit runs out, retries temporary failures and
 * only sends the token to the server itself.
 */
public class ForgejoClient {

    /** Items per page; Forgejo caps this at its MAX_RESPONSE_ITEMS setting, 50 by default. */
    public static final int PAGE_SIZE = 50;

    private static final Pattern RATE_REMAINING = Pattern.compile("\\br=(\\d+)");
    private static final Pattern RATE_RESET = Pattern.compile("\\bt=(\\d+)");
    private static final Duration MAX_WAIT = Duration.ofMinutes(10);

    static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    public record Page<T>(List<T> items, Integer total) {
    }

    private final URI base;
    private final String token;
    private final HttpClient http;
    private BooleanSupplier cancelled = () -> false;
    private Consumer<String> notices = message -> { };

    public ForgejoClient(String baseUrl, String token) {
        this.base = normalize(baseUrl);
        this.token = token == null || token.isBlank() ? null : token.strip();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** {@code https://host/path}, without a trailing slash or {@code /api/v1}. */
    public static URI normalize(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.toLowerCase(Locale.ROOT).endsWith("/api/v1")) {
            url = url.substring(0, url.length() - "/api/v1".length());
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("import.url-invalid", baseUrl);
        }
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw ApiException.badRequest("import.url-invalid", baseUrl);
        }
        return uri;
    }

    public URI base() {
        return base;
    }

    public String baseUrl() {
        return base.toString();
    }

    public boolean hasToken() {
        return token != null;
    }

    /** Makes waiting and retrying stop when a job is cancelled, and reports waits. */
    public ForgejoClient watch(BooleanSupplier cancelled, Consumer<String> notices) {
        this.cancelled = cancelled;
        this.notices = notices;
        return this;
    }

    public <T> T get(String path, Class<T> type) {
        return read(send(api(path, Map.of())), JSON.constructType(type));
    }

    /** A list the server returns in one go (e.g. the comments of an issue). */
    public <T> List<T> list(String path, Class<T> element) {
        List<T> items = read(send(api(path, Map.of())),
                JSON.getTypeFactory().constructCollectionType(List.class, element));
        return withoutNulls(items);
    }

    /** One page of a list. {@code total} is the X-Total-Count header when the server sends it. */
    public <T> Page<T> page(String path, Map<String, String> query, int page, Class<T> element) {
        HttpResponse<byte[]> response = send(api(path, pageQuery(query, page)));
        List<T> items = read(response, JSON.getTypeFactory().constructCollectionType(List.class, element));
        Integer total = response.headers().firstValue("X-Total-Count").map(v -> {
            try {
                return Integer.valueOf(v.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
        return new Page<>(withoutNulls(items), total);
    }

    /** All items of a list, page by page. */
    public <T> List<T> all(String path, Map<String, String> query, Class<T> element) {
        List<T> all = new java.util.ArrayList<>();
        for (int page = 1; ; page++) {
            HttpResponse<byte[]> response = send(api(path, pageQuery(query, page)));
            List<T> items = read(response, JSON.getTypeFactory().constructCollectionType(List.class, element));
            int received = items == null ? 0 : items.size();
            all.addAll(withoutNulls(items));
            if (received < PAGE_SIZE || page > 10_000) {
                return all;
            }
        }
    }

    /**
     * Downloads a file. The token is only sent when the file is on the Forgejo server itself.
     * Reading more than {@code maxBytes} fails with {@link TooLargeException}.
     */
    public InputStream download(String url, long maxBytes) throws IOException {
        URI uri = base.resolve(url);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET();
        if (token != null && sameServer(uri)) {
            request.header("Authorization", "token " + token);
        }
        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " for " + uri);
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maxBytes) {
            response.body().close();
            throw new TooLargeException(declared);
        }
        return new LimitedInputStream(response.body(), maxBytes);
    }

    public static final class TooLargeException extends IOException {
        public TooLargeException(long size) {
            super("larger than allowed (" + size + " bytes)");
        }
    }

    // --- internals -----------------------------------------------------------------------

    private static Map<String, String> pageQuery(Map<String, String> query, int page) {
        Map<String, String> params = new java.util.LinkedHashMap<>(query);
        params.put("page", String.valueOf(page));
        params.put("limit", String.valueOf(PAGE_SIZE));
        return params;
    }

    /** Forgejo answers {@code null} for entries it cannot show, e.g. references to deleted issues. */
    private static <T> List<T> withoutNulls(List<T> items) {
        return items == null ? List.of() : items.stream().filter(java.util.Objects::nonNull).toList();
    }

    private boolean sameServer(URI uri) {
        return base.getHost().equalsIgnoreCase(uri.getHost()) && base.getPort() == uri.getPort()
                && base.getScheme().equalsIgnoreCase(uri.getScheme());
    }

    private URI api(String path, Map<String, String> query) {
        String q = query.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return URI.create(base + "/api/v1" + path + (q.isEmpty() ? "" : "?" + q));
    }

    private HttpResponse<byte[]> send(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json").GET();
        if (token != null) {
            builder.header("Authorization", "token " + token);
        }
        HttpRequest request = builder.build();
        for (int attempt = 1; ; attempt++) {
            checkCancelled();
            HttpResponse<byte[]> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (attempt < 4) {
                    pause(Duration.ofSeconds(5L * attempt), null);
                    continue;
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "import.unreachable", base.getHost(),
                        String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
            int status = response.statusCode();
            if (status == 429 || status >= 500 && attempt < 4) {
                Duration wait = status == 429 ? retryAfter(response) : Duration.ofSeconds(5L * attempt);
                if (attempt < 8) {
                    pause(wait, status == 429 ? "The server's rate limit was reached; waiting " + wait.toSeconds()
                            + " seconds." : null);
                    continue;
                }
            }
            if (status == 200) {
                respectRateLimit(response);
                return response;
            }
            throw switch (status) {
                case 401 -> new ApiException(HttpStatus.BAD_REQUEST, "import.token-refused");
                case 403 -> new ApiException(HttpStatus.BAD_REQUEST, "import.forbidden", uri.getPath());
                case 404 -> new ApiException(HttpStatus.NOT_FOUND, "import.not-found", uri.getPath());
                default -> new ApiException(HttpStatus.BAD_GATEWAY, "import.server-error", status, uri.getPath());
            };
        }
    }

    /** Forgejo announces its remaining requests (e.g. {@code "baseline";r=1998;t=600}); wait before it runs out. */
    private void respectRateLimit(HttpResponse<?> response) {
        String header = response.headers().firstValue("RateLimit").orElse(null);
        if (header == null) {
            return;
        }
        Matcher remaining = RATE_REMAINING.matcher(header);
        Matcher reset = RATE_RESET.matcher(header);
        if (remaining.find() && reset.find() && Long.parseLong(remaining.group(1)) <= 2) {
            Duration wait = Duration.ofSeconds(Long.parseLong(reset.group(1)) + 1);
            pause(wait, "The server's rate limit is almost used up; waiting " + wait.toSeconds() + " seconds.");
        }
    }

    private Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(v -> {
            try {
                return Duration.ofSeconds(Long.parseLong(v.strip()));
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(Duration.ofSeconds(60));
    }

    private void pause(Duration wait, String notice) {
        Duration capped = wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
        if (notice != null) {
            notices.accept(notice);
        }
        long until = System.nanoTime() + capped.toNanos();
        while (System.nanoTime() < until) {
            checkCancelled();
            try {
                Thread.sleep(Math.min(500, Math.max(1, (until - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean()) {
            throw new CancelledException();
        }
    }

    private static <T> T read(HttpResponse<byte[]> response, JavaType type) {
        try {
            return JSON.readValue(response.body(), type);
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "import.bad-response", response.uri().getPath());
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {

        private final long max;
        private long read;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws TooLargeException {
            read += n;
            if (read > max) {
                throw new TooLargeException(read);
            }
        }
    }
}
