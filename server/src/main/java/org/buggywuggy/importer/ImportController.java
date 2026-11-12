/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import jakarta.validation.Valid;
import java.util.List;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportJobs.JobResponse;
import org.buggywuggy.importer.ImportJobs.LogLine;
import org.buggywuggy.importer.forgejo.ForgejoDtos.Analysis;
import org.buggywuggy.importer.forgejo.ForgejoDtos.AnalysisRequest;
import org.buggywuggy.importer.forgejo.ForgejoDtos.ConnectionRequest;
import org.buggywuggy.importer.forgejo.ForgejoDtos.ConnectionResponse;
import org.buggywuggy.importer.forgejo.ForgejoDtos.RepositoryInfo;
import org.buggywuggy.importer.forgejo.ForgejoDtos.RepositoryRequest;
import org.buggywuggy.importer.forgejo.ForgejoDtos.RetryRequest;
import org.buggywuggy.importer.forgejo.ForgejoDtos.StartRequest;
import org.buggywuggy.importer.forgejo.ForgejoImportService;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Importing from other trackers. Requires the ADMINISTER global permission (see
 * SecurityConfiguration). Tokens are sent in request bodies, never in URLs, so they do not end
 * up in access logs.
 */
@RestController
@RequestMapping("/api/v1/admin/import")
public class ImportController {

    private final ForgejoImportService forgejo;
    private final ImportJobs jobs;
    private final ImportRunner runner;

    public ImportController(ForgejoImportService forgejo, ImportJobs jobs, ImportRunner runner) {
        this.forgejo = forgejo;
        this.jobs = jobs;
        this.runner = runner;
    }

    @PostMapping("/forgejo/connection")
    public ConnectionResponse testForgejo(@Valid @RequestBody ConnectionRequest request) {
        return forgejo.test(request.url(), request.token());
    }

    @PostMapping("/forgejo/repositories")
    public List<RepositoryInfo> forgejoRepositories(@Valid @RequestBody RepositoryRequest request) {
        return forgejo.repositories(request.url(), request.token(), request.query());
    }

    @PostMapping("/forgejo/analysis")
    public Analysis analyzeForgejo(@Valid @RequestBody AnalysisRequest request) {
        return forgejo.analyze(request.url(), request.token(), request.repository());
    }

    @PostMapping("/forgejo/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse startForgejo(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @Valid @RequestBody StartRequest request) {
        return forgejo.start(user, request);
    }

    @GetMapping("/jobs")
    public List<JobResponse> jobs() {
        return jobs.list(runner::hasToken);
    }

    @GetMapping("/jobs/{id}")
    public JobResponse job(@PathVariable long id) {
        return jobs.get(id, runner::hasToken).orElseThrow(() -> ApiException.notFound("import.job-not-found", id));
    }

    @GetMapping("/jobs/{id}/log")
    public List<LogLine> log(@PathVariable long id, @RequestParam(defaultValue = "0") long after) {
        job(id);
        return jobs.log(id, after);
    }

    @PostMapping("/jobs/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable long id) {
        job(id);
        runner.cancel(id);
    }

    @PostMapping("/jobs/{id}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse retry(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable long id,
            @Valid @RequestBody(required = false) RetryRequest request) {
        return forgejo.retry(user, id, request == null ? null : request.token());
    }
}
