/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.jira;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
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
import org.buggywuggy.importer.jira.JiraApi.Permissions;
import org.buggywuggy.importer.jira.JiraApi.Project;
import org.buggywuggy.importer.jira.JiraApi.ProjectPage;
import org.buggywuggy.importer.jira.JiraApi.ServerInfo;
import org.buggywuggy.importer.jira.JiraApi.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JavaType;

/**
 * Jira Cloud and Jira Server / Data Center, through REST API v2, which both have. The token
 * is {@code e-mail:API token} (Cloud) or {@code username:password} for basic authentication,
 * or a personal access token (Server / Data Center) on its own; none for public projects.
 */
@Component
public class JiraConnector implements Connector {

    public static final String KIND = "JIRA";

    static final int PAGE_SIZE = 100;

    /** A project key, as Jira allows them. */
    static final String PROJECT_KEY = "[A-Z][A-Z0-9_]{0,254}";

    /**
     * Jira Cloud refuses with 429 (sometimes 503) and Retry-After when its limit is reached;
     * Server and Data Center only with 429 when rate limiting is turned on.
     */
    static final ReadOnlyHttp.RateLimits LIMITS = new ReadOnlyHttp.RateLimits() {
        @Override
        public Optional<Instant> retryAt(HttpResponse<?> response, Instant now) {
            int status = response.statusCode();
            Optional<Long> retryAfter = response.headers().firstValue("Retry-After").flatMap(JiraConnector::number);
            if (status == 429) {
                return Optional.of(retryAfter.map(now::plusSeconds).or(() -> reset(response))
                        .filter(at -> at.isAfter(now)).orElse(now.plusSeconds(60)));
            }
            return status == 503 ? retryAfter.map(now::plusSeconds) : Optional.empty();
        }

        @Override
        public Optional<Instant> pauseUntil(HttpResponse<?> response, Instant now) {
            Optional<Long> remaining = response.headers().firstValue("X-RateLimit-Remaining")
                    .flatMap(JiraConnector::number);
            return remaining.isPresent() && remaining.get() <= 1 ? reset(response).filter(at -> at.isAfter(now))
                    : Optional.empty();
        }

        /** X-RateLimit-Reset is a time (ISO 8601). */
        private Optional<Instant> reset(HttpResponse<?> response) {
            return response.headers().firstValue("X-RateLimit-Reset").flatMap(v -> {
                try {
                    return Optional.of(OffsetDateTime.parse(v.strip()).toInstant());
                } catch (DateTimeParseException e) {
                    return Optional.empty();
                }
            });
        }
    };

    private static Optional<Long> number(String value) {
        try {
            return Optional.of(Long.parseLong(value.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private final Clock clock;

    public JiraConnector(Clock clock) {
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

    /** ABC, typed in any case. */
    @Override
    public String path(String repository) {
        String key = repository == null ? "" : repository.strip().toUpperCase(Locale.ROOT);
        if (!key.matches(PROJECT_KEY)) {
            throw ApiException.badRequest("import.repository-invalid", repository);
        }
        return key;
    }

    static URI web(String url) {
        URI uri = ReadOnlyHttp.server(url, "/rest/api/2", "/rest/api/3", "/rest/api/latest");
        String path = uri.getPath() == null ? "" : uri.getPath();
        // A link to a page instead of the address of Jira itself.
        for (String page : List.of("/secure/", "/browse/", "/jira/software/", "/projects/")) {
            int at = path.indexOf(page);
            if (at >= 0) {
                return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path.substring(0, at));
            }
        }
        return uri;
    }

    /** The API of one server, for one token. */
    final class Api {
        final URI web;
        final URI api;
        final ReadOnlyHttp http;
        private ServerInfo info;

        Api(String url, String token) {
            this.web = web(url);
            this.api = URI.create(web + "/rest/api/2");
            String host = web.getHost().toLowerCase(Locale.ROOT);
            this.http = new ReadOnlyHttp(host, Set.of(host), authorization(token),
                    Map.of("Accept", "application/json", "User-Agent", "BuggyWuggy-importer"), LIMITS, clock);
        }

        URI uri(String path, Map<String, String> query) {
            String q = query.entrySet().stream()
                    .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            return URI.create(api + path + (q.isEmpty() ? "" : "?" + q));
        }

        <T> T get(String path, Map<String, String> query, Class<T> type) {
            return read(http.get(uri(path, query)), ReadOnlyHttp.CAMEL_CASE_JSON.constructType(type));
        }

        <T> List<T> list(String path, Map<String, String> query, Class<T> element) {
            List<T> items = read(http.get(uri(path, query)), ReadOnlyHttp.CAMEL_CASE_JSON.getTypeFactory()
                    .constructCollectionType(List.class, element));
            return items == null ? List.of() : items.stream().filter(java.util.Objects::nonNull).toList();
        }

        <T> T read(ReadOnlyHttp.Response response, JavaType type) {
            return ReadOnlyHttp.read(response, type, ReadOnlyHttp.CAMEL_CASE_JSON);
        }

        ServerInfo info() {
            if (info == null) {
                info = get("/serverInfo", Map.of(), ServerInfo.class);
            }
            return info;
        }

        boolean cloud() {
            return "cloud".equalsIgnoreCase(info().deploymentType())
                    || web.getHost().toLowerCase(Locale.ROOT).endsWith(".atlassian.net");
        }
    }

    /** e-mail:token or username:password → basic; anything else is a bearer token. */
    static String authorization(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String value = token.strip();
        return value.contains(":")
                ? "Basic " + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                : "Bearer " + value;
    }

    Api api(String url, String token) {
        return new Api(url, token);
    }

    @Override
    public ConnectionResponse test(String url, String token) {
        Api api = api(url, token);
        ServerInfo info = api.info();
        String version = api.cloud() ? "Jira Cloud" : "Jira " + ("DataCenter".equalsIgnoreCase(info.deploymentType())
                ? "Data Center " : "Server ") + (info.version() == null ? "" : info.version());
        if (!api.http.hasToken()) {
            return new ConnectionResponse(api.web.toString(), version.strip(), null, false);
        }
        User me = api.get("/myself", Map.of(), User.class);
        boolean admin = false;
        try {
            Permissions permissions = api.get("/mypermissions", Map.of("permissions", "ADMINISTER"),
                    Permissions.class);
            admin = permissions.permissions() != null && permissions.permissions().containsKey("ADMINISTER")
                    && permissions.permissions().get("ADMINISTER").havePermission();
        } catch (ApiException e) {
            // Older servers without the parameter: not important enough to fail for.
        }
        String login = me.name() != null && !me.name().isBlank() ? me.name()
                : me.emailAddress() != null && !me.emailAddress().isBlank() ? me.emailAddress() : me.displayName();
        return new ConnectionResponse(api.web.toString(), version.strip(), login, admin);
    }

    @Override
    public List<RepositoryInfo> repositories(String url, String token, String query) {
        Api api = api(url, token);
        String q = query == null ? "" : query.strip();
        if (q.toUpperCase(Locale.ROOT).matches(PROJECT_KEY)) {
            try {
                return List.of(JiraSource.info(api, api.get("/project/" + q.toUpperCase(Locale.ROOT), Map.of(),
                        Project.class)));
            } catch (ApiException e) {
                if (e.status() != HttpStatus.NOT_FOUND) {
                    throw e;
                }
            }
        }
        List<Project> projects;
        if (api.cloud()) {
            projects = api.get("/project/search", q.isEmpty() ? Map.of("maxResults", "50")
                    : Map.of("maxResults", "50", "query", q), ProjectPage.class).values();
        } else {
            String lower = q.toLowerCase(Locale.ROOT);
            projects = api.list("/project", Map.of(), Project.class).stream()
                    .filter(p -> lower.isEmpty() || contains(p.key(), lower) || contains(p.name(), lower))
                    .limit(50).toList();
        }
        return projects == null ? List.of() : projects.stream().map(p -> JiraSource.info(api, p)).toList();
    }

    private static boolean contains(String value, String part) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(part);
    }

    @Override
    public Source open(String url, String token, String repository) {
        Api api = api(url, token);
        Project project = api.get("/project/" + repository, Map.of(), Project.class);
        return new JiraSource(api, project);
    }
}
