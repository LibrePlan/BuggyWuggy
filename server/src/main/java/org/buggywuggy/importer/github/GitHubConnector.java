/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.github;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.Connector;
import org.buggywuggy.importer.ImportDtos.ConnectionResponse;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.ReadOnlyHttp;
import org.buggywuggy.importer.Source;
import org.buggywuggy.importer.github.GitHubApi.Meta;
import org.buggywuggy.importer.github.GitHubApi.Repository;
import org.buggywuggy.importer.github.GitHubApi.SearchResult;
import org.buggywuggy.importer.github.GitHubApi.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * GitHub.com and GitHub Enterprise Server, through the REST API. A token is required: without
 * one GitHub allows only 60 requests an hour, too few for any real repository.
 */
@Component
public class GitHubConnector implements Connector {

    public static final String KIND = "GITHUB";

    static final int PAGE_SIZE = 100;

    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");

    /**
     * GitHub announces its budget in x-ratelimit-* headers (5000 an hour with a token) and
     * refuses with 403 or 429 when it is used up, or with Retry-After for its secondary limits.
     */
    static final ReadOnlyHttp.RateLimits LIMITS = new ReadOnlyHttp.RateLimits() {
        @Override
        public Optional<Instant> retryAt(HttpResponse<?> response, Instant now) {
            int status = response.statusCode();
            if (status != 403 && status != 429) {
                return Optional.empty();
            }
            Optional<Long> retryAfter = number(response, "Retry-After");
            if (retryAfter.isPresent()) {
                return Optional.of(now.plusSeconds(retryAfter.get()));
            }
            if (number(response, "x-ratelimit-remaining").orElse(1L) == 0) {
                return Optional.of(reset(response, now));
            }
            return status == 429 ? Optional.of(now.plusSeconds(60)) : Optional.empty();
        }

        @Override
        public Optional<Instant> pauseUntil(HttpResponse<?> response, Instant now) {
            Optional<Long> remaining = number(response, "x-ratelimit-remaining");
            return remaining.isPresent() && remaining.get() <= 2 ? Optional.of(reset(response, now))
                    : Optional.empty();
        }

        private Instant reset(HttpResponse<?> response, Instant now) {
            return number(response, "x-ratelimit-reset").map(epoch -> Instant.ofEpochSecond(epoch + 1))
                    .filter(at -> at.isAfter(now)).orElse(now.plusSeconds(60));
        }
    };

    private static Optional<Long> number(HttpResponse<?> response, String header) {
        return response.headers().firstValue(header).flatMap(v -> {
            try {
                return Optional.of(Long.parseLong(v.strip()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private final Clock clock;

    public GitHubConnector(Clock clock) {
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

    /** The web address: https://github.com, or the address of an Enterprise server. */
    static URI web(String url) {
        URI uri = ReadOnlyHttp.server(url == null || url.isBlank() ? "https://github.com" : url, "/api/v3");
        if ("api.github.com".equalsIgnoreCase(uri.getHost()) || "www.github.com".equalsIgnoreCase(uri.getHost())) {
            return URI.create("https://github.com");
        }
        return uri;
    }

    /** The API of one server, for one token. */
    final class Api {
        final URI web;
        final URI api;
        final ReadOnlyHttp http;

        Api(String url, String token) {
            if (token == null || token.isBlank()) {
                throw ApiException.badRequest("import.token-required");
            }
            this.web = web(url);
            this.api = isGitHubCom() ? URI.create("https://api.github.com") : URI.create(web + "/api/v3");
            String host = api.getHost().toLowerCase(Locale.ROOT);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/vnd.github+json");
            headers.put("X-GitHub-Api-Version", "2022-11-28");
            headers.put("User-Agent", "BuggyWuggy-importer");
            this.http = new ReadOnlyHttp(host, Set.of(host), "Bearer " + token.strip(), headers, LIMITS, clock);
        }

        boolean isGitHubCom() {
            return "github.com".equalsIgnoreCase(web.getHost());
        }

        URI uri(String path, Map<String, String> query) {
            String q = query.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            return URI.create(api + path + (q.isEmpty() ? "" : "?" + q));
        }

        <T> T get(String path, Class<T> type) {
            return http.get(uri(path, Map.of()), type);
        }

        /** All items of a list, following the {@code next} links GitHub sends. */
        <T> List<T> all(String path, Map<String, String> query, Class<T> element) {
            Map<String, String> params = new LinkedHashMap<>(query);
            params.put("per_page", String.valueOf(PAGE_SIZE));
            List<T> all = new ArrayList<>();
            URI next = uri(path, params);
            for (int page = 0; next != null && page < 10_000; page++) {
                ReadOnlyHttp.Response response = http.get(next);
                all.addAll(ReadOnlyHttp.list(response, element));
                next = response.headers().firstValue("Link").map(NEXT::matcher).filter(Matcher::find)
                        .map(m -> URI.create(m.group(1)))
                        // Only follow links to the API itself.
                        .filter(u -> api.getHost().equalsIgnoreCase(u.getHost())).orElse(null);
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
        User user = api.get("/user", User.class);
        String version = "GitHub.com";
        if (!api.isGitHubCom()) {
            Meta meta = api.get("/meta", Meta.class);
            version = "GitHub Enterprise Server" + (meta.installedVersion() == null ? "" : " "
                    + meta.installedVersion());
        }
        return new ConnectionResponse(api.web.toString(), version, user.login(), user.siteAdmin());
    }

    @Override
    public List<RepositoryInfo> repositories(String url, String token, String query) {
        Api api = api(url, token);
        String q = query == null ? "" : query.strip();
        if (q.matches("[\\w.\\-]+/[\\w.\\-]+")) {
            try {
                return List.of(GitHubSource.info(api.get("/repos/" + q, Repository.class)));
            } catch (ApiException e) {
                if (e.status() != HttpStatus.NOT_FOUND) {
                    throw e;
                }
            }
        }
        if (q.isEmpty()) {
            return ReadOnlyHttp.list(api.http.get(api.uri("/user/repos", Map.of("per_page", "50",
                    "sort", "updated"))), Repository.class).stream().map(GitHubSource::info).toList();
        }
        SearchResult result = api.http.get(api.uri("/search/repositories", Map.of("q", q, "per_page", "50")),
                SearchResult.class);
        return result.items() == null ? List.of() : result.items().stream().map(GitHubSource::info).toList();
    }

    @Override
    public Source open(String url, String token, String repository) {
        Api api = api(url, token);
        Repository repo = api.get("/repos/" + repository, Repository.class);
        if (!repo.hasIssues()) {
            throw ApiException.badRequest("import.issues-disabled", repository);
        }
        return new GitHubSource(api, repository, repo);
    }
}
