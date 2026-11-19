/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.gitlab;

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
import org.buggywuggy.importer.IssueImporter;
import org.buggywuggy.importer.ReadOnlyHttp;
import org.buggywuggy.importer.Source;
import org.buggywuggy.importer.gitlab.GitLabApi.Project;
import org.buggywuggy.importer.gitlab.GitLabApi.User;
import org.buggywuggy.importer.gitlab.GitLabApi.Version;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * gitlab.com and self-managed GitLab, through the REST API ({@code /api/v4}). A token is
 * optional: public projects can be read without one.
 */
@Component
public class GitLabConnector implements Connector {

    public static final String KIND = "GITLAB";

    static final int PAGE_SIZE = 100;

    /** GitLab announces its budget in RateLimit-* headers and refuses with 429 when it is used up. */
    static final ReadOnlyHttp.RateLimits LIMITS = new ReadOnlyHttp.RateLimits() {
        @Override
        public Optional<Instant> retryAt(HttpResponse<?> response, Instant now) {
            if (response.statusCode() != 429) {
                return Optional.empty();
            }
            Optional<Long> retryAfter = number(response, "Retry-After");
            if (retryAfter.isPresent()) {
                return Optional.of(now.plusSeconds(retryAfter.get()));
            }
            return Optional.of(reset(response, now));
        }

        @Override
        public Optional<Instant> pauseUntil(HttpResponse<?> response, Instant now) {
            Optional<Long> remaining = number(response, "RateLimit-Remaining");
            return remaining.isPresent() && remaining.get() <= 2 ? Optional.of(reset(response, now))
                    : Optional.empty();
        }

        private Instant reset(HttpResponse<?> response, Instant now) {
            return number(response, "RateLimit-Reset").map(epoch -> Instant.ofEpochSecond(epoch + 1))
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

    public GitLabConnector(Clock clock) {
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

    static URI web(String url) {
        return ReadOnlyHttp.server(url == null || url.isBlank() ? "https://gitlab.com" : url, "/api/v4");
    }

    /** The API of one server, for one token. */
    final class Api {
        final URI web;
        final URI api;
        final ReadOnlyHttp http;

        Api(String url, String token) {
            this.web = web(url);
            this.api = URI.create(web + "/api/v4");
            String host = web.getHost().toLowerCase(Locale.ROOT);
            String auth = token == null || token.isBlank() ? null : "Bearer " + token.strip();
            this.http = new ReadOnlyHttp(host, Set.of(host), auth,
                    Map.of("Accept", "application/json", "User-Agent", "BuggyWuggy-importer"), LIMITS, clock);
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

        /** All items of a list, page by page as long as GitLab announces an x-next-page. */
        <T> List<T> all(String path, Map<String, String> query, Class<T> element) {
            List<T> all = new ArrayList<>();
            String page = "1";
            for (int pages = 0; page != null && pages < 10_000; pages++) {
                Map<String, String> params = new LinkedHashMap<>(query);
                params.put("per_page", String.valueOf(PAGE_SIZE));
                params.put("page", page);
                ReadOnlyHttp.Response response = http.get(uri(path, params));
                all.addAll(ReadOnlyHttp.list(response, element));
                page = response.headers().firstValue("x-next-page").map(String::strip).filter(p -> !p.isEmpty())
                        .orElse(null);
            }
            return all;
        }
    }

    /** A project path as one URL segment: group%2Fsubgroup%2Fproject. */
    static String encoded(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    Api api(String url, String token) {
        return new Api(url, token);
    }

    @Override
    public ConnectionResponse test(String url, String token) {
        Api api = api(url, token);
        if (!api.http.hasToken()) {
            // Without a token GitLab does not tell its version; any public list shows it is GitLab.
            List<Project> some = ReadOnlyHttp.list(api.http.get(api.uri("/projects", Map.of("per_page", "1"))),
                    Project.class);
            // Large public servers (gitlab.com, gitlab.gnome.org) list projects and issues to
            // anyone, but labels, comments and history only to logged-in users: say so now, not
            // halfway through an import.
            if (!some.isEmpty()) {
                try {
                    api.http.get(api.uri("/projects/" + some.get(0).id() + "/labels", Map.of("per_page", "1")));
                } catch (ApiException e) {
                    if ("import.login-required".equals(e.messageKey())) {
                        throw ApiException.badRequest("import.login-required", "labels, comments and history");
                    }
                    // Anything else (a project without labels access, …) is for the import to find.
                }
            }
            return new ConnectionResponse(api.web.toString(), "", null, false);
        }
        Version version = api.get("/version", Version.class);
        User user = api.get("/user", User.class);
        return new ConnectionResponse(api.web.toString(), version.version(), user.username(), user.isAdmin());
    }

    @Override
    public List<RepositoryInfo> repositories(String url, String token, String query) {
        Api api = api(url, token);
        String q = query == null ? "" : query.strip();
        if (q.matches(IssueImporter.PATH)) {
            try {
                return List.of(GitLabSource.info(api.get("/projects/" + encoded(q), Project.class)));
            } catch (ApiException e) {
                if (e.status() != HttpStatus.NOT_FOUND) {
                    throw e;
                }
            }
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("per_page", "50");
        params.put("order_by", "last_activity_at");
        if (q.isEmpty()) {
            if (!api.http.hasToken()) {
                return List.of();
            }
            params.put("membership", "true");
        } else {
            params.put("search", q);
            params.put("search_namespaces", "true");
        }
        return ReadOnlyHttp.list(api.http.get(api.uri("/projects", params)), Project.class).stream()
                .map(GitLabSource::info).toList();
    }

    @Override
    public Source open(String url, String token, String repository) {
        Api api = api(url, token);
        Project project = api.get("/projects/" + encoded(repository), Project.class);
        if (!GitLabSource.info(project).hasIssues()) {
            throw ApiException.badRequest("import.issues-disabled", repository);
        }
        return new GitLabSource(api, repository, project);
    }
}
