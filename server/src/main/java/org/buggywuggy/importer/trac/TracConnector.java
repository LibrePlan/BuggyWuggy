/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.trac;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.Connector;
import org.buggywuggy.importer.ImportDtos.ConnectionResponse;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.ReadOnlyHttp;
import org.buggywuggy.importer.Source;
import org.buggywuggy.importer.XmlRpc;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Trac, through the XML-RPC interface of the TracXMLRPC plugin (/rpc, or /login/rpc when
 * logging in), which the user needs the XML_RPC permission for. Only the read-only methods of
 * {@link ReadOnlyHttp#READ_ONLY_RPC_METHODS} are called. The token is
 * {@code username:password} for basic authentication; none for anonymous access. A Trac
 * environment is one project: its name is the last part of the address.
 */
@Component
public class TracConnector implements Connector {

    public static final String KIND = "TRAC";

    /** The name of an environment, as used for the source. */
    static final String NAME = "[\\w.\\-]{1,100}";

    /** Trac has no rate limit of its own; a proxy in front of it may. */
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

    public TracConnector(Clock clock) {
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

    @Override
    public String path(String repository) {
        String name = repository == null ? "" : repository.strip();
        if (!name.matches(NAME)) {
            throw ApiException.badRequest("import.repository-invalid", repository);
        }
        return name;
    }

    static URI web(String url) {
        URI uri = ReadOnlyHttp.server(url, "/login/rpc", "/rpc", "/login/xmlrpc", "/xmlrpc", "/login/jsonrpc",
                "/jsonrpc");
        String path = uri.getPath() == null ? "" : uri.getPath();
        // A link to a page instead of the address of the environment.
        for (String page : List.of("/ticket", "/query", "/report", "/wiki", "/timeline", "/roadmap", "/milestone",
                "/browser", "/newticket", "/login", "/admin", "/attachment/", "/raw-attachment/", "/changeset",
                "/search", "/prefs", "/about")) {
            int at = path.indexOf(page);
            if (at >= 0 && (path.length() == at + page.length() || page.endsWith("/")
                    || "/?.".indexOf(path.charAt(at + page.length())) >= 0)) {
                return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path.substring(0, at));
            }
        }
        return uri;
    }

    /** https://trac.example.org/widget → widget; https://trac.example.org → trac.example.org */
    static String name(URI web) {
        String path = web.getPath() == null ? "" : web.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        String name = last.isBlank() ? web.getHost() : last;
        return name.matches(NAME) ? name : web.getHost();
    }

    /** The XML-RPC interface of one environment, for one login. */
    final class Api {
        final URI web;
        final URI endpoint;
        final ReadOnlyHttp http;
        final String user;

        Api(String url, String token) {
            this.web = web(url);
            String host = web.getHost().toLowerCase(Locale.ROOT);
            String credentials = token == null || token.isBlank() ? null : token.strip();
            this.user = credentials == null ? null : credentials.contains(":")
                    ? credentials.substring(0, credentials.indexOf(':')) : credentials;
            // Trac only asks for a login under /login.
            this.endpoint = URI.create(web + (credentials == null ? "/rpc" : "/login/rpc"));
            this.http = new ReadOnlyHttp(host, Set.of(host), credentials == null ? null : "Basic "
                    + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)),
                    Map.of("User-Agent", "BuggyWuggy-importer"), LIMITS, clock);
        }

        Object call(String method, Object... params) {
            try {
                return http.rpc(endpoint, method, params);
            } catch (XmlRpc.Fault fault) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "import.rpc-fault", method, fault.getMessage());
            } catch (ApiException e) {
                if (e.status() == HttpStatus.NOT_FOUND) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "import.rpc-missing", endpoint.toString());
                }
                throw e;
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> list(String method, Object... params) {
            Object result = call(method, params);
            return result instanceof List<?> list ? (List<Object>) list : List.of();
        }
    }

    Api api(String url, String token) {
        return new Api(url, token);
    }

    @Override
    public ConnectionResponse test(String url, String token) {
        Api api = api(url, token);
        return new ConnectionResponse(api.web.toString(), version(api), api.user, false);
    }

    /** [epoch, major, minor] → Trac (XML-RPC 1.2) */
    private static String version(Api api) {
        List<Object> version = api.list("system.getAPIVersion");
        return version.size() >= 3 ? "Trac (XML-RPC " + version.get(1) + "." + version.get(2) + ")" : "Trac";
    }

    /** An environment is one project: the list is that project. */
    @Override
    public List<RepositoryInfo> repositories(String url, String token, String query) {
        Api api = api(url, token);
        version(api);
        return List.of(TracSource.info(api));
    }

    @Override
    public Source open(String url, String token, String repository) {
        Api api = api(url, token);
        if (!name(api.web).equalsIgnoreCase(repository)) {
            throw ApiException.badRequest("import.repository-invalid", repository);
        }
        return new TracSource(api);
    }
}
