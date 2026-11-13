/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.redmine;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
import org.buggywuggy.importer.redmine.RedmineApi.Project;
import org.buggywuggy.importer.redmine.RedmineApi.ProjectPage;
import org.buggywuggy.importer.redmine.RedmineApi.ProjectWrapper;
import org.buggywuggy.importer.redmine.RedmineApi.User;
import org.buggywuggy.importer.redmine.RedmineApi.UserWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Redmine, through its REST API (JSON). The token is an API key (My account → API access key)
 * or {@code username:password}; none for public projects. Both go as basic authentication,
 * which Redmine accepts with the key as username. For a key, the administrator must have
 * turned on the REST web service (Administration → Settings → API).
 */
@Component
public class RedmineConnector implements Connector {

    public static final String KIND = "REDMINE";

    static final int PAGE_SIZE = 100;

    /** A project identifier, as Redmine allows them (lower case, not only digits). */
    static final String IDENTIFIER = "[a-z][a-z0-9_\\-]{0,99}";

    /** Redmine has no rate limit of its own; a proxy in front of it may. */
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

    public RedmineConnector(Clock clock) {
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

    /** The identifier of a project, typed in any case. */
    @Override
    public String path(String repository) {
        String identifier = repository == null ? "" : repository.strip().toLowerCase(Locale.ROOT);
        if (!identifier.matches(IDENTIFIER)) {
            throw ApiException.badRequest("import.repository-invalid", repository);
        }
        return identifier;
    }

    static URI web(String url) {
        URI uri = ReadOnlyHttp.server(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        // A link to a page instead of the address of Redmine itself.
        for (String page : List.of("/projects", "/issues", "/my/", "/login", "/admin", "/users/", "/attachments/",
                "/versions/")) {
            int at = path.indexOf(page);
            if (at >= 0 && (path.length() == at + page.length() || page.endsWith("/")
                    || "/?.".indexOf(path.charAt(at + page.length())) >= 0)) {
                return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path.substring(0, at));
            }
        }
        return uri;
    }

    /** The API of one server, for one token. */
    final class Api {
        final URI web;
        final ReadOnlyHttp http;

        Api(String url, String token) {
            this.web = web(url);
            String host = web.getHost().toLowerCase(Locale.ROOT);
            this.http = new ReadOnlyHttp(host, Set.of(host), authorization(token),
                    Map.of("Accept", "application/json", "User-Agent", "BuggyWuggy-importer"), LIMITS, clock);
        }

        URI uri(String path, Map<String, String> query) {
            String q = query.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            return URI.create(web + path + (q.isEmpty() ? "" : "?" + q));
        }

        <T> T get(String path, Map<String, String> query, Class<T> type) {
            return http.get(uri(path, query), type);
        }

        /** All pages of a list in Redmine's style (offset, limit, total_count). */
        <P, T> List<T> all(String path, Map<String, String> query, Class<P> page,
                java.util.function.Function<P, List<T>> items, java.util.function.ToIntFunction<P> total) {
            List<T> all = new ArrayList<>();
            for (int offset = 0; offset < 10_000_000; ) {
                Map<String, String> params = new LinkedHashMap<>(query);
                params.put("offset", String.valueOf(offset));
                params.put("limit", String.valueOf(PAGE_SIZE));
                P result = get(path, params, page);
                List<T> found = items.apply(result) == null ? List.of() : items.apply(result);
                all.addAll(found);
                offset += found.size();
                if (found.isEmpty() || offset >= total.applyAsInt(result)) {
                    break;
                }
            }
            return all;
        }
    }

    /** username:password, or an API key as username (Redmine ignores the password then). */
    static String authorization(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String value = token.strip();
        String credentials = value.contains(":") ? value : value + ":x";
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    Api api(String url, String token) {
        return new Api(url, token);
    }

    @Override
    public ConnectionResponse test(String url, String token) {
        Api api = api(url, token);
        if (!api.http.hasToken()) {
            // Anything that only Redmine answers, and that public projects can read.
            api.get("/projects.json", Map.of("limit", "1"), ProjectPage.class);
            return new ConnectionResponse(api.web.toString(), "Redmine", null, false);
        }
        User me = api.get("/users/current.json", Map.of(), UserWrapper.class).user();
        if (me == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "import.bad-response", "/users/current.json");
        }
        String login = me.login() != null && !me.login().isBlank() ? me.login()
                : (nullToEmpty(me.firstname()) + " " + nullToEmpty(me.lastname())).strip();
        return new ConnectionResponse(api.web.toString(), "Redmine", login, Boolean.TRUE.equals(me.admin()));
    }

    @Override
    public List<RepositoryInfo> repositories(String url, String token, String query) {
        Api api = api(url, token);
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.matches(IDENTIFIER)) {
            try {
                return List.of(RedmineSource.info(api, api.get("/projects/" + q + ".json", Map.of(),
                        ProjectWrapper.class).project()));
            } catch (ApiException e) {
                if (e.status() != HttpStatus.NOT_FOUND) {
                    throw e;
                }
            }
        }
        // Redmine cannot search projects by name: filter the list.
        return api.all("/projects.json", Map.of(), ProjectPage.class, ProjectPage::projects, ProjectPage::totalCount)
                .stream()
                .filter(p -> p.identifier() != null && (q.isEmpty() || contains(p.identifier(), q)
                        || contains(p.name(), q)))
                .limit(50).map(p -> RedmineSource.info(api, p)).toList();
    }

    private static boolean contains(String value, String part) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(part);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public Source open(String url, String token, String repository) {
        Api api = api(url, token);
        Project project = api.get("/projects/" + repository + ".json", Map.of("include", "enabled_modules"),
                ProjectWrapper.class).project();
        if (project == null || project.identifier() == null) {
            throw ApiException.badRequest("import.repository-invalid", repository);
        }
        if (project.enabledModules() != null && project.enabledModules().stream()
                .noneMatch(m -> "issue_tracking".equals(m.name()))) {
            throw ApiException.badRequest("import.issues-disabled", repository);
        }
        return new RedmineSource(api, project);
    }
}
