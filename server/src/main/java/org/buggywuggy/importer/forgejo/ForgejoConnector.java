/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.forgejo;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.Connector;
import org.buggywuggy.importer.ImportDtos.ConnectionResponse;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.IssueImporter;
import org.buggywuggy.importer.ReadOnlyHttp;
import org.buggywuggy.importer.Source;
import org.buggywuggy.importer.forgejo.ForgejoApi.Repository;
import org.buggywuggy.importer.forgejo.ForgejoApi.SearchResult;
import org.buggywuggy.importer.forgejo.ForgejoApi.User;
import org.buggywuggy.importer.forgejo.ForgejoApi.Version;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Forgejo and Gitea servers, through their REST API ({@code /api/v1}). */
@Component
public class ForgejoConnector implements Connector {

    public static final String KIND = "FORGEJO";

    /** Items per page; Forgejo caps this at its MAX_RESPONSE_ITEMS setting, 50 by default. */
    static final int PAGE_SIZE = 50;

    private static final Pattern RATE_REMAINING = Pattern.compile("\\br=(\\d+)");
    private static final Pattern RATE_RESET = Pattern.compile("\\bt=(\\d+)");

    /** Forgejo answers 429 with Retry-After, and announces its budget as {@code "baseline";r=1998;t=600}. */
    static final ReadOnlyHttp.RateLimits LIMITS = new ReadOnlyHttp.RateLimits() {
        @Override
        public Optional<Instant> retryAt(HttpResponse<?> response, Instant now) {
            if (response.statusCode() != 429) {
                return Optional.empty();
            }
            long seconds = response.headers().firstValue("Retry-After").map(v -> {
                try {
                    return Long.parseLong(v.strip());
                } catch (NumberFormatException e) {
                    return 60L;
                }
            }).orElse(60L);
            return Optional.of(now.plusSeconds(seconds));
        }

        @Override
        public Optional<Instant> pauseUntil(HttpResponse<?> response, Instant now) {
            String header = response.headers().firstValue("RateLimit").orElse(null);
            if (header == null) {
                return Optional.empty();
            }
            Matcher remaining = RATE_REMAINING.matcher(header);
            Matcher reset = RATE_RESET.matcher(header);
            if (remaining.find() && reset.find() && Long.parseLong(remaining.group(1)) <= 2) {
                return Optional.of(now.plusSeconds(Long.parseLong(reset.group(1)) + 1));
            }
            return Optional.empty();
        }
    };

    private final Clock clock;

    public ForgejoConnector(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String baseUrl(String url) {
        return ReadOnlyHttp.server(url, "/api/v1").toString();
    }

    /** The API of one server, for one token. */
    final class Api {
        final URI base;
        final ReadOnlyHttp http;

        Api(String url, String token) {
            this.base = ReadOnlyHttp.server(url, "/api/v1");
            String host = base.getHost().toLowerCase(Locale.ROOT);
            String auth = token == null || token.isBlank() ? null : "token " + token.strip();
            this.http = new ReadOnlyHttp(host, Set.of(host), auth, Map.of("Accept", "application/json"), LIMITS,
                    clock);
        }

        URI uri(String path, Map<String, String> query) {
            String q = query.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            return URI.create(base + "/api/v1" + path + (q.isEmpty() ? "" : "?" + q));
        }

        <T> T get(String path, Class<T> type) {
            return http.get(uri(path, Map.of()), type);
        }

        /** A list the server returns in one go (e.g. the comments of an issue). */
        <T> List<T> list(String path, Class<T> element) {
            return ReadOnlyHttp.list(http.get(uri(path, Map.of())), element);
        }

        /** All items of a list, page by page. */
        <T> List<T> all(String path, Map<String, String> query, Class<T> element) {
            List<T> all = new java.util.ArrayList<>();
            for (int page = 1; page <= 10_000; page++) {
                Map<String, String> params = new java.util.LinkedHashMap<>(query);
                params.put("page", String.valueOf(page));
                params.put("limit", String.valueOf(PAGE_SIZE));
                ReadOnlyHttp.Response response = http.get(uri(path, params));
                List<T> items = ReadOnlyHttp.read(response, ReadOnlyHttp.JSON.getTypeFactory()
                        .constructCollectionType(List.class, element));
                // Count with the null entries Forgejo sends for things it cannot show.
                int received = items == null ? 0 : items.size();
                if (items != null) {
                    items.stream().filter(java.util.Objects::nonNull).forEach(all::add);
                }
                if (received < PAGE_SIZE) {
                    break;
                }
            }
            return all;
        }
    }

    Api api(String url, String token) {
        return new Api(url, token);
    }

    @Override
    public ConnectionResponse test(String url, String token) {
        Api api = api(url, token);
        Version version = api.get("/version", Version.class);
        User user = api.http.hasToken() ? api.get("/user", User.class) : null;
        return new ConnectionResponse(api.base.toString(), version.version(), user == null ? null : user.login(),
                user != null && user.isAdmin());
    }

    @Override
    public List<RepositoryInfo> repositories(String url, String token, String query) {
        Api api = api(url, token);
        String q = query == null ? "" : query.strip();
        if (q.matches(IssueImporter.PATH)) {
            try {
                return List.of(ForgejoSource.info(api.get("/repos/" + q, Repository.class)));
            } catch (ApiException e) {
                if (e.status() != HttpStatus.NOT_FOUND) {
                    throw e;
                }
            }
        }
        if (q.isEmpty() && api.http.hasToken()) {
            return ReadOnlyHttp.list(api.http.get(api.uri("/user/repos", Map.of("page", "1",
                    "limit", String.valueOf(PAGE_SIZE)))), Repository.class).stream().map(ForgejoSource::info).toList();
        }
        SearchResult result = api.http.get(api.uri("/repos/search", Map.of("q", q, "limit",
                String.valueOf(PAGE_SIZE))), SearchResult.class);
        return result.data() == null ? List.of() : result.data().stream().map(ForgejoSource::info).toList();
    }

    @Override
    public Source open(String url, String token, String repository) {
        Api api = api(url, token);
        Repository repo = api.get("/repos/" + repository, Repository.class);
        if (!ForgejoSource.info(repo).hasIssues()) {
            throw ApiException.badRequest("import.issues-disabled", repository);
        }
        return new ForgejoSource(api, repository, repo);
    }
}
