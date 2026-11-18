/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportRunner.CancelledException;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP client of all importers. It can only <b>read</b>, so an import can never change
 * the system it reads from: it sends GET requests, and the only other request is an XML-RPC
 * call (a POST, as XML-RPC requires) of one of the {@link #READ_ONLY_RPC_METHODS}, which this
 * class writes itself.
 *
 * <p>It retries temporary failures, waits when the server's rate limit runs out (asking the
 * {@link RateLimits} of the source when and how long), follows redirects itself, and only
 * sends the access token to the hosts of the source, never to a host a redirect points to.
 */
public final class ReadOnlyHttp {

    /**
     * The XML-RPC methods {@link #rpc} may call: those of Trac's XmlRpcPlugin that only read.
     * Methods that change something (ticket.create, ticket.update, …) are not on the list,
     * and neither is system.multicall, which could wrap them.
     */
    public static final Set<String> READ_ONLY_RPC_METHODS = Set.of("system.getAPIVersion", "ticket.query",
            "ticket.get", "ticket.changeLog", "ticket.listAttachments", "ticket.getTicketFields",
            "ticket.milestone.getAll", "ticket.milestone.get", "ticket.component.getAll", "ticket.component.get",
            "ticket.type.getAll", "ticket.priority.getAll", "ticket.resolution.getAll", "ticket.status.getAll",
            "ticket.version.getAll");

    /** Waiting is never longer than this at once (GitHub resets its limit every hour). */
    public static final Duration MAX_WAIT = Duration.ofMinutes(65);
    private static final int MAX_REDIRECTS = 5;

    /** Reads the snake_case JSON of tracker APIs; unknown fields are ignored. */
    public static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    /** Reads the camelCase JSON of other APIs (Jira); unknown fields are ignored. */
    public static final JsonMapper CAMEL_CASE_JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    /** How a source announces its rate limit. */
    public interface RateLimits {

        /** When to try again after this response, if it was refused for the rate limit. */
        Optional<Instant> retryAt(HttpResponse<?> response, Instant now);

        /** When to pause before the next request because the limit is nearly used up. */
        Optional<Instant> pauseUntil(HttpResponse<?> response, Instant now);
    }

    /** Reports waits, so the administrator can see why an import does not move. */
    public interface Waits {
        void waiting(String reason, Instant until);

        void resumed();
    }

    public record Response(int status, HttpHeaders headers, byte[] body, URI uri) {
    }

    public record Download(InputStream content, URI uri, String contentType, long length) {
    }

    private final String host;
    private final Set<String> tokenHosts;
    /** The headers with the access token (usually only Authorization); empty without one. */
    private final Map<String, String> credentials;
    private final Map<String, String> headers;
    private final RateLimits limits;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private BooleanSupplier cancelled = () -> false;
    private Waits waits = new Waits() {
        public void waiting(String reason, Instant until) {
        }

        public void resumed() {
        }
    };

    /**
     * @param host          the server name for messages
     * @param tokenHosts    the hosts that may receive {@code authorization} (also after a redirect)
     * @param authorization the Authorization header, or null
     * @param headers       extra headers for every request (e.g. Accept, User-Agent)
     */
    public ReadOnlyHttp(String host, Set<String> tokenHosts, String authorization, Map<String, String> headers,
            RateLimits limits, Clock clock) {
        this(host, tokenHosts, authorization == null ? Map.of() : Map.of("Authorization", authorization), headers,
                limits, clock);
    }

    private ReadOnlyHttp(String host, Set<String> tokenHosts, Map<String, String> credentials,
            Map<String, String> headers, RateLimits limits, Clock clock) {
        this.host = host;
        this.tokenHosts = tokenHosts;
        this.credentials = Map.copyOf(credentials);
        this.headers = headers;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * For sources that take the access token in headers of their own instead of Authorization
     * (e.g. Bugzilla's X-BUGZILLA-API-KEY): {@code credentials} are those headers, sent only to
     * {@code tokenHosts}, as Authorization otherwise; empty without a token.
     */
    public static ReadOnlyHttp withCredentialHeaders(String host, Set<String> tokenHosts,
            Map<String, String> credentials, Map<String, String> headers, RateLimits limits, Clock clock) {
        return new ReadOnlyHttp(host, tokenHosts, credentials, headers, limits, clock);
    }

    /**
     * The address of a server as typed by an administrator: {@code https://host/path},
     * without a trailing slash or one of {@code apiSuffixes} (e.g. /api/v1).
     */
    public static URI server(String url, String... apiSuffixes) {
        String value = url == null ? "" : url.strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        for (String suffix : apiSuffixes) {
            if (value.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                value = value.substring(0, value.length() - suffix.length());
            }
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("import.url-invalid", url);
        }
        if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()))) {
            throw ApiException.badRequest("import.url-invalid", url);
        }
        return uri;
    }

    public ReadOnlyHttp watch(BooleanSupplier cancelled, Waits waits) {
        this.cancelled = cancelled;
        this.waits = waits;
        return this;
    }

    public boolean hasToken() {
        return !credentials.isEmpty();
    }

    /** GETs an API resource; anything but 200 becomes an {@link ApiException} with a readable message. */
    public Response get(URI start) {
        return send(start, null);
    }

    /**
     * Calls a read-only XML-RPC method (see {@link #READ_ONLY_RPC_METHODS}) and returns its
     * result. A fault of the server becomes {@link XmlRpc.Fault}.
     *
     * @throws IllegalArgumentException for any other method
     */
    public Object rpc(URI endpoint, String method, Object... params) {
        if (!READ_ONLY_RPC_METHODS.contains(method)) {
            throw new IllegalArgumentException("Not a read-only XML-RPC method: " + method);
        }
        Response response = send(endpoint, XmlRpc.bytes(XmlRpc.call(method, List.of(params))));
        try {
            return XmlRpc.result(response.body());
        } catch (XmlRpc.Fault fault) {
            throw fault;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "import.bad-response", response.uri().getPath());
        }
    }

    /** A GET, or with {@code rpc} an XML-RPC call. */
    private Response send(URI start, byte[] rpc) {
        URI uri = start;
        int redirects = 0;
        for (int attempt = 1; ; attempt++) {
            checkCancelled();
            HttpResponse<byte[]> response;
            try {
                response = http.send(rpc == null ? request(uri, Duration.ofSeconds(60), true) : rpcRequest(uri, rpc),
                        HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                if (attempt < 4) {
                    pause(clock.instant().plusSeconds(5L * attempt), null);
                    continue;
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "import.unreachable", host,
                        String.valueOf(e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
            int status = response.statusCode();
            Optional<Instant> retry = limits.retryAt(response, clock.instant());
            if (retry.isPresent() && attempt < 10) {
                pause(retry.get(), "rate limit of " + host);
                continue;
            }
            if (status >= 500 && attempt < 4) {
                pause(clock.instant().plusSeconds(5L * attempt), null);
                continue;
            }
            if (status >= 300 && status < 400 && redirects < MAX_REDIRECTS
                    && response.headers().firstValue("Location").isPresent()
                    && (rpc == null || sameSource(uri.resolve(response.headers().firstValue("Location").get())))) {
                // E.g. a renamed repository. The token only goes along to the hosts of the source.
                uri = uri.resolve(response.headers().firstValue("Location").get());
                redirects++;
                attempt = 0;
                continue;
            }
            if (status == 200) {
                limits.pauseUntil(response, clock.instant())
                        .ifPresent(until -> pause(until, "rate limit of " + host));
                return new Response(status, response.headers(), response.body(), uri);
            }
            throw switch (status) {
                case 401 -> new ApiException(HttpStatus.BAD_REQUEST, "import.token-refused");
                case 403 -> new ApiException(HttpStatus.BAD_REQUEST, "import.forbidden", uri.getPath());
                case 404 -> new ApiException(HttpStatus.NOT_FOUND, "import.not-found", uri.getPath());
                default -> new ApiException(HttpStatus.BAD_GATEWAY, "import.server-error", status, uri.getPath());
            };
        }
    }

    public <T> T get(URI uri, Class<T> type) {
        return read(get(uri), JSON.constructType(type));
    }

    /** The response as a list; {@code null} entries (some servers send them) are left out. */
    public static <T> List<T> list(Response response, Class<T> element) {
        List<T> items = read(response, JSON.getTypeFactory().constructCollectionType(List.class, element));
        return items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
    }

    public static <T> T read(Response response, JavaType type) {
        return read(response, type, JSON);
    }

    public static <T> T read(Response response, JavaType type, JsonMapper mapper) {
        try {
            return mapper.readValue(response.body(), type);
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "import.bad-response", response.uri().getPath());
        }
    }

    /**
     * Downloads a file, following redirects. Reading more than {@code maxBytes} fails with
     * {@link TooLargeException}.
     */
    public Download download(URI start, long maxBytes) throws IOException {
        URI uri = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkCancelled();
            HttpResponse<InputStream> response;
            try {
                response = http.send(request(uri, Duration.ofMinutes(5), false), HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                response.body().close();
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("redirect without location"));
                uri = uri.resolve(location);
                continue;
            }
            if (status != 200) {
                response.body().close();
                throw new IOException("HTTP " + status);
            }
            long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (length > maxBytes) {
                response.body().close();
                throw new TooLargeException(length);
            }
            return new Download(new LimitedInputStream(response.body(), maxBytes), uri,
                    response.headers().firstValue("Content-Type").orElse(null), length);
        }
        throw new IOException("too many redirects");
    }

    public static final class TooLargeException extends IOException {
        public TooLargeException(long size) {
            super("larger than allowed (" + size + " bytes)");
        }
    }

    /** XML-RPC calls are only sent on (e.g. from http to https) to the hosts of the source. */
    private boolean sameSource(URI uri) {
        return uri.getHost() != null && tokenHosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
    }

    private HttpRequest rpcRequest(URI uri, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Accept") && !name.equalsIgnoreCase("Content-Type")) {
                builder.header(name, value);
            }
        });
        builder.header("Content-Type", "text/xml; charset=utf-8").header("Accept", "text/xml");
        if (sameSource(uri)) {
            credentials.forEach(builder::header);
        }
        return builder.build();
    }

    /** Files may be on other hosts (e.g. storage a redirect points to): they only get the User-Agent. */
    private HttpRequest request(URI uri, Duration timeout, boolean api) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).GET();
        headers.forEach((name, value) -> {
            if (api || name.equalsIgnoreCase("User-Agent")) {
                builder.header(name, value);
            }
        });
        if (sameSource(uri)) {
            credentials.forEach(builder::header);
        }
        return builder.build();
    }

    private void pause(Instant until, String reason) {
        Instant cap = clock.instant().plus(MAX_WAIT);
        Instant end = until.isAfter(cap) ? cap : until;
        if (reason != null) {
            waits.waiting(reason, end);
        }
        try {
            while (clock.instant().isBefore(end)) {
                checkCancelled();
                long millis = Duration.between(clock.instant(), end).toMillis();
                Thread.sleep(Math.max(1, Math.min(500, millis)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } finally {
            if (reason != null) {
                waits.resumed();
            }
        }
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean()) {
            throw new CancelledException();
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
