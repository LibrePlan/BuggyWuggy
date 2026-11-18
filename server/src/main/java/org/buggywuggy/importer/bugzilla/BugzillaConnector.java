/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.bugzilla;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.Connector;
import org.buggywuggy.importer.ImportDtos.ConnectionResponse;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.ReadOnlyHttp;
import org.buggywuggy.importer.Source;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Product;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Products;
import org.buggywuggy.importer.bugzilla.BugzillaApi.Version;
import org.buggywuggy.importer.bugzilla.BugzillaApi.WhoAmI;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Bugzilla 5.0 and newer, through its REST API (JSON). A product is a project. The token is an
 * API key (Preferences → API Keys) or {@code login:password}; none for public products.
 * Bugzilla does not take them as basic authentication but in headers of its own
 * (X-BUGZILLA-API-KEY, X-BUGZILLA-LOGIN and X-BUGZILLA-PASSWORD), which are only sent to the
 * Bugzilla itself. The API is under /rest, or under /rest.cgi where the web server does not
 * rewrite /rest.
 */
@Component
public class BugzillaConnector implements Connector {

    public static final String KIND = "BUGZILLA";

    static final int PAGE_SIZE = 100;

    /** Bugzilla has no rate limit of its own; a proxy in front of it may. */
    static final ReadOnlyHttp.RateLimits LIMITS = new ReadOnlyHttp.RateLimits() {
        @Override
        public Optional<Instant> retryAt(HttpResponse<?> response, Instant now) {
            int status = response.statusCode();
            if (status != 429 && status != 503) {
                return Optional.empty();
            }
            Optional<Instant> after = response.headers().firstValue("Retry-After").flatMap(v -> {
                try {
                    return Optional.of(now.plusSeconds(Long.parseLong(v.strip())));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            });
            return status == 429 ? Optional.of(after.orElse(now.plusSeconds(60))) : after;
        }

        @Override
        public Optional<Instant> pauseUntil(HttpResponse<?> response, Instant now) {
            return Optional.empty();
        }
    };

    private final Clock clock;

    public BugzillaConnector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String baseUrl(String url) {
        return web(url).toString();
    }

    /** The name of a product, as Bugzilla shows it (it may contain spaces). */
    @Override
    public String path(String repository) {
        String name = repository == null ? "" : repository.strip();
        if (name.isEmpty() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("import.repository-invalid", repository);
        }
        return name;
    }

    /** The address of Bugzilla itself, also when a page or the API was typed. */
    static URI web(String url) {
        URI uri = ReadOnlyHttp.server(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String last = path.substring(slash + 1);
        if (last.endsWith(".cgi") || last.equals("rest")) {
            path = path.substring(0, Math.max(slash, 0));
        } else {
            for (String api : List.of("/rest/", "/rest.cgi/")) {
                int at = path.indexOf(api);
                if (at >= 0) {
                    path = path.substring(0, at);
                    break;
                }
            }
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path);
    }

    /** The API of one server, for one token. */
    final class Api {
        final URI web;
        final ReadOnlyHttp http;
        /** /rest or /rest.cgi, found at the first request. */
        private String rest;

        Api(String url, String token) {
            this.web = web(url);
            String host = web.getHost().toLowerCase(Locale.ROOT);
            this.http = ReadOnlyHttp.withCredentialHeaders(host, Set.of(host), credentials(token),
                    Map.of("Accept", "application/json", "User-Agent", "BuggyWuggy-importer"), LIMITS, clock);
        }

        String rest() {
            if (rest == null) {
                try {
                    http.get(uri("/rest", "/version", Map.of()), Version.class);
                    rest = "/rest";
                } catch (ApiException e) {
                    if (e.status() != HttpStatus.NOT_FOUND) {
                        throw e;
                    }
                    rest = "/rest.cgi";
                }
            }
            return rest;
        }

        private URI uri(String rest, String path, Map<String, ?> query) {
            List<String> pairs = new ArrayList<>();
            query.forEach((name, value) -> {
                for (Object v : value instanceof List<?> list ? list : List.of(value)) {
                    pairs.add(encode(name) + "=" + encode(String.valueOf(v)));
                }
            });
            return URI.create(web + rest + path + (pairs.isEmpty() ? "" : "?" + String.join("&", pairs)));
        }

        /** {@code query}: values may be lists, for parameters that repeat (ids=1&ids=2). */
        <T> T get(String path, Map<String, ?> query, Class<T> type) {
            return http.get(uri(rest(), path, query), type);
        }

        URI webUri(String page, Map<String, String> query) {
            return URI.create(web + "/" + page + (query.isEmpty() ? "" : "?" + query.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"))));
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** login:password, or an API key. */
    static Map<String, String> credentials(String token) {
        if (token == null || token.isBlank()) {
            return Map.of();
        }
        String value = token.strip();
        int colon = value.indexOf(':');
        if (colon > 0) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-BUGZILLA-LOGIN", value.substring(0, colon));
            headers.put("X-BUGZILLA-PASSWORD", value.substring(colon + 1));
            return headers;
        }
        return Map.of("X-BUGZILLA-API-KEY", value);
    }

    Api api(String url, String token) {
        return new Api(url, token);
    }

    @Override
    public ConnectionResponse test(String url, String token) {
        Api api = api(url, token);
        Version version = api.get("/version", Map.of(), Version.class);
        if (version == null || version.version() == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "import.bad-response", "/rest/version");
        }
        String name = "Bugzilla " + version.version();
        if (!api.http.hasToken()) {
            return new ConnectionResponse(api.web.toString(), name, null, false);
        }
        String login;
        try {
            WhoAmI me = api.get("/whoami", Map.of(), WhoAmI.class);
            login = me == null || me.name() == null ? null : me.name();
        } catch (ApiException e) {
            if (e.status() != HttpStatus.NOT_FOUND) {
                throw e;
            }
            // Bugzilla 5.0 has no whoami: check the key by reading something that needs it.
            api.get("/user", Map.of("match", "*", "limit", "1", "include_fields", "id"), BugzillaApi.Users.class);
            login = null;
        }
        String typed = credentials(token).get("X-BUGZILLA-LOGIN");
        return new ConnectionResponse(api.web.toString(), name,
                login != null ? login : typed != null ? typed : "API key", false);
    }

    @Override
    public List<RepositoryInfo> repositories(String url, String token, String query) {
        Api api = api(url, token);
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Product> products = api.get("/product", Map.of("type", "accessible",
                "include_fields", "id,name,description,is_active"), Products.class).products();
        return (products == null ? List.<Product>of() : products).stream()
                .filter(p -> p.name() != null && (q.isEmpty() || p.name().toLowerCase(Locale.ROOT).contains(q)))
                .sorted((a, b) -> Boolean.compare(!a.name().equalsIgnoreCase(q), !b.name().equalsIgnoreCase(q)))
                .limit(50).map(p -> BugzillaSource.info(api, p)).toList();
    }

    @Override
    public Source open(String url, String token, String repository) {
        Api api = api(url, token);
        Products found = api.get("/product", Map.of("names", repository), Products.class);
        Product product = found == null || found.products() == null ? null : found.products().stream()
                .filter(p -> p.name() != null && p.name().equalsIgnoreCase(repository)).findFirst().orElse(null);
        if (product == null) {
            throw ApiException.badRequest("import.repository-invalid", repository);
        }
        return new BugzillaSource(api, product);
    }
}
