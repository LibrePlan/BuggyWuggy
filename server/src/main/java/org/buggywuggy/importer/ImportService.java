/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportDtos.Analysis;
import org.buggywuggy.importer.ImportDtos.ConnectionResponse;
import org.buggywuggy.importer.ImportDtos.Options;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.ImportDtos.StartRequest;
import org.buggywuggy.importer.ImportJobs.Definition;
import org.buggywuggy.importer.ImportJobs.JobResponse;
import org.buggywuggy.project.ProjectDtos.CreateProjectRequest;
import org.buggywuggy.project.ProjectService;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** The steps of the import wizard, and starting and retrying jobs, for every kind of source. */
@Service
public class ImportService {

    private final List<Connector> connectors;
    private final IssueImporter importer;
    private final ImportJobs jobs;
    private final ImportRunner runner;
    private final ProjectService projects;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public ImportService(List<Connector> connectors, IssueImporter importer, ImportJobs jobs, ImportRunner runner,
            ProjectService projects, NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.connectors = connectors;
        this.importer = importer;
        this.jobs = jobs;
        this.runner = runner;
        this.projects = projects;
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The connector for a kind in a URL (github) or in the database (GITHUB). */
    public Connector connector(String kind) {
        return connectors.stream().filter(c -> c.slug().equalsIgnoreCase(kind) || c.kind().equals(kind)).findFirst()
                .orElseThrow(() -> ApiException.notFound("import.kind-unsupported", kind));
    }

    public ConnectionResponse test(String kind, String url, String token) {
        return connector(kind).test(url, token);
    }

    public List<RepositoryInfo> repositories(String kind, String url, String token, String query) {
        return connector(kind).repositories(url, token, query);
    }

    public Analysis analyze(String kind, String url, String token, String repository) {
        return importer.analyze(connector(kind).open(url, token, IssueImporter.path(repository)));
    }

    public JobResponse start(String kind, BuggyWuggyPrincipal admin, StartRequest request) {
        Connector connector = connector(kind);
        String path = IssueImporter.path(request.repository());
        if (jobs.anyRunning()) {
            throw conflict("import.running");
        }
        // Fail early, before a project is created, when the repository cannot be read.
        Source source = connector.open(request.url(), request.token(), path);
        Options options = new Options(path, request.issueTypeId(), request.labels(), request.users(),
                !Boolean.FALSE.equals(request.matchUsernames()));
        validate(options);
        long projectId = project(admin, request);
        long sourceId = jobs.sourceId(connector.kind(), source.baseUrl(), path);
        long jobId = jobs.create(sourceId, projectId, json.writeValueAsString(options), admin.id());
        launch(jobId, connector, sourceId, projectId, options, source.baseUrl(), request.token(), admin.id());
        return jobs.get(jobId, runner::hasToken).orElseThrow();
    }

    /** Runs a finished job again with the same settings; only what is new gets imported. */
    public JobResponse retry(BuggyWuggyPrincipal admin, long jobId, String token) {
        Definition old = jobs.definition(jobId).orElseThrow(() -> ApiException.notFound("import.job-not-found", jobId));
        Connector connector = connectors.stream().filter(c -> c.kind().equals(old.kind())).findFirst()
                .orElseThrow(() -> badRequest("import.kind-unsupported", old.kind()));
        if (old.status() == ImportJobs.Status.RUNNING || jobs.anyRunning()) {
            throw conflict("import.running");
        }
        if (old.projectId() == null) {
            throw conflict("import.project-gone");
        }
        String useToken = token != null && !token.isBlank() ? token : runner.token(jobId);
        Options options = json.readValue(old.options(), Options.class);
        long newJob = jobs.create(old.sourceId(), old.projectId(), old.options(), admin.id());
        launch(newJob, connector, old.sourceId(), old.projectId(), options, old.baseUrl(), useToken, admin.id());
        return jobs.get(newJob, runner::hasToken).orElseThrow();
    }

    private void launch(long jobId, Connector connector, long sourceId, long projectId, Options options, String url,
            String token, long adminId) {
        runner.start(jobId, token, context -> importer.run(context, connector.open(url, token, options.repository()),
                sourceId, projectId, options, adminId));
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
            options.users().values().stream().filter(Objects::nonNull).forEach(id -> {
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
