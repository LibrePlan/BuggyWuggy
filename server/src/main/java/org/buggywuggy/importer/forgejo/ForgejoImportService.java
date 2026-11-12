/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.forgejo;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;

import java.util.List;
import java.util.Map;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportJobs;
import org.buggywuggy.importer.ImportJobs.Definition;
import org.buggywuggy.importer.ImportJobs.JobResponse;
import org.buggywuggy.importer.ImportRunner;
import org.buggywuggy.importer.forgejo.ForgejoApi.Repository;
import org.buggywuggy.importer.forgejo.ForgejoApi.SearchResult;
import org.buggywuggy.importer.forgejo.ForgejoApi.User;
import org.buggywuggy.importer.forgejo.ForgejoApi.Version;
import org.buggywuggy.importer.forgejo.ForgejoDtos.Analysis;
import org.buggywuggy.importer.forgejo.ForgejoDtos.ConnectionResponse;
import org.buggywuggy.importer.forgejo.ForgejoDtos.Options;
import org.buggywuggy.importer.forgejo.ForgejoDtos.RepositoryInfo;
import org.buggywuggy.importer.forgejo.ForgejoDtos.StartRequest;
import org.buggywuggy.project.ProjectDtos.CreateProjectRequest;
import org.buggywuggy.project.ProjectService;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** The steps of the Forgejo import wizard, and starting and retrying its jobs. */
@Service
public class ForgejoImportService {

    private final ForgejoImporter importer;
    private final ImportJobs jobs;
    private final ImportRunner runner;
    private final ProjectService projects;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public ForgejoImportService(ForgejoImporter importer, ImportJobs jobs, ImportRunner runner,
            ProjectService projects, NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.importer = importer;
        this.jobs = jobs;
        this.runner = runner;
        this.projects = projects;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Creates the client; tests replace it to talk to a fake server. */
    protected ForgejoClient client(String url, String token) {
        return new ForgejoClient(url, token);
    }

    public ConnectionResponse test(String url, String token) {
        ForgejoClient client = client(url, token);
        Version version = client.get("/version", Version.class);
        User user = client.hasToken() ? client.get("/user", User.class) : null;
        return new ConnectionResponse(client.baseUrl(), version.version(), user == null ? null : user.login(),
                user != null && user.isAdmin());
    }

    public List<RepositoryInfo> repositories(String url, String token, String query) {
        ForgejoClient client = client(url, token);
        String q = query == null ? "" : query.strip();
        if (q.matches("[\\w.\\-]+/[\\w.\\-]+")) {
            try {
                return List.of(ForgejoImporter.info(client.get("/repos/" + q, Repository.class)));
            } catch (ApiException e) {
                if (e.status() != HttpStatus.NOT_FOUND) {
                    throw e;
                }
            }
        }
        if (q.isEmpty() && client.hasToken()) {
            return client.page("/user/repos", Map.of(), 1, Repository.class).items().stream()
                    .map(ForgejoImporter::info).toList();
        }
        SearchResult result = client.get("/repos/search?q=" + java.net.URLEncoder.encode(q,
                java.nio.charset.StandardCharsets.UTF_8) + "&limit=" + ForgejoClient.PAGE_SIZE, SearchResult.class);
        return result.data() == null ? List.of() : result.data().stream().map(ForgejoImporter::info).toList();
    }

    public Analysis analyze(String url, String token, String repository) {
        return importer.analyze(client(url, token), repository);
    }

    public JobResponse start(BuggyWuggyPrincipal admin, StartRequest request) {
        String path = ForgejoImporter.path(request.repository());
        ForgejoClient client = client(request.url(), request.token());
        if (jobs.anyRunning()) {
            throw conflict("import.running");
        }
        // Fail early, before a project is created, when the repository cannot be read.
        ForgejoImporter.repository(client, path);
        Options options = new Options(path, request.issueTypeId(), request.labels(), request.users(),
                !Boolean.FALSE.equals(request.matchUsernames()));
        validate(options);
        long projectId = project(admin, request);
        long sourceId = jobs.sourceId(ForgejoImporter.KIND, client.baseUrl(), path);
        long jobId = jobs.create(sourceId, projectId, json.writeValueAsString(options), admin.id());
        launch(jobId, sourceId, projectId, options, client.baseUrl(), request.token(), admin.id());
        return jobs.get(jobId, runner::hasToken).orElseThrow();
    }

    /** Runs a finished job again with the same settings; only what is new gets imported. */
    public JobResponse retry(BuggyWuggyPrincipal admin, long jobId, String token) {
        Definition old = jobs.definition(jobId).orElseThrow(() -> ApiException.notFound("import.job-not-found", jobId));
        if (!ForgejoImporter.KIND.equals(old.kind())) {
            throw badRequest("import.kind-unsupported", old.kind());
        }
        if (old.status() == ImportJobs.Status.RUNNING) {
            throw conflict("import.running");
        }
        if (jobs.anyRunning()) {
            throw conflict("import.running");
        }
        if (old.projectId() == null) {
            throw conflict("import.project-gone");
        }
        String useToken = token != null && !token.isBlank() ? token : runner.token(jobId);
        Options options = json.readValue(old.options(), Options.class);
        long newJob = jobs.create(old.sourceId(), old.projectId(), old.options(), admin.id());
        launch(newJob, old.sourceId(), old.projectId(), options, old.baseUrl(), useToken, admin.id());
        return jobs.get(newJob, runner::hasToken).orElseThrow();
    }

    private void launch(long jobId, long sourceId, long projectId, Options options, String url, String token,
            long adminId) {
        runner.start(jobId, token, context -> importer.run(context, client(url, token), sourceId, projectId, options,
                adminId));
    }

    private void validate(Options options) {
        Integer level = jdbc.queryForList("select hierarchy_level from issue_type where id = :id",
                Map.of("id", options.issueTypeId()), Integer.class).stream().findFirst().orElse(null);
        if (level == null || level != 0) {
            throw badRequest("import.issue-type-invalid");
        }
        if (options.labels() != null) {
            options.labels().forEach((label, rule) -> {
                String table = switch (rule.target()) {
                    case ISSUE_TYPE -> "issue_type";
                    case PRIORITY -> "priority";
                    default -> null;
                };
                if (table == null) {
                    return;
                }
                boolean ok = rule.id() != null && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists (select 1 from " + table + " where id = :id"
                                + (table.equals("issue_type") ? " and hierarchy_level = 0" : "") + ")",
                        Map.of("id", rule.id()), Boolean.class));
                if (!ok) {
                    throw badRequest("import.label-rule-invalid", label);
                }
            });
        }
        if (options.users() != null) {
            options.users().values().stream().filter(java.util.Objects::nonNull).forEach(id -> {
                if (!Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists (select 1 from user_account where id = :id)", Map.of("id", id), Boolean.class))) {
                    throw badRequest("import.user-invalid", id);
                }
            });
        }
    }

    private long project(BuggyWuggyPrincipal admin, StartRequest request) {
        if ((request.projectId() == null) == (request.newProject() == null)) {
            throw badRequest("import.project-required");
        }
        if (request.newProject() != null) {
            return projects.create(admin, new CreateProjectRequest(request.newProject().key(),
                    request.newProject().name(), null, null, null)).id();
        }
        Boolean archived = jdbc.queryForList("select archived from project where id = :id",
                Map.of("id", request.projectId()), Boolean.class).stream().findFirst()
                .orElseThrow(() -> badRequest("import.project-required"));
        if (archived) {
            throw conflict("project.archived", request.projectId());
        }
        return request.projectId();
    }
}
