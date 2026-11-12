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
import org.buggywuggy.importer.ImportDtos.Analysis;
import org.buggywuggy.importer.ImportDtos.AnalysisRequest;
import org.buggywuggy.importer.ImportDtos.ConnectionRequest;
import org.buggywuggy.importer.ImportDtos.ConnectionResponse;
import org.buggywuggy.importer.ImportDtos.RepositoryInfo;
import org.buggywuggy.importer.ImportDtos.RepositoryRequest;
import org.buggywuggy.importer.ImportDtos.RetryRequest;
import org.buggywuggy.importer.ImportDtos.StartRequest;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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
 * up in access logs. {@code kind} is a source such as forgejo or github.
 */
@RestController
@RequestMapping("/api/v1/admin/import")
public class ImportController {

    private final ImportService service;
    private final ImportJobs jobs;
    private final ImportRunner runner;

    public ImportController(ImportService service, ImportJobs jobs, ImportRunner runner) {
        this.service = service;
        this.jobs = jobs;
        this.runner = runner;
    }

    @PostMapping("/{kind}/connection")
    public ConnectionResponse testConnection(@PathVariable String kind,
            @Valid @RequestBody ConnectionRequest request) {
        return service.test(kind, request.url(), request.token());
    }

    @PostMapping("/{kind}/repositories")
    public List<RepositoryInfo> repositories(@PathVariable String kind,
            @Valid @RequestBody RepositoryRequest request) {
        return service.repositories(kind, request.url(), request.token(), request.query());
    }

    @PostMapping("/{kind}/analysis")
    public Analysis analyze(@PathVariable String kind, @Valid @RequestBody AnalysisRequest request) {
        return service.analyze(kind, request.url(), request.token(), request.repository());
    }

    @PostMapping("/{kind}/jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse start(@PathVariable String kind, @AuthenticationPrincipal BuggyWuggyPrincipal user,
            @Valid @RequestBody StartRequest request) {
        return service.start(kind, user, request);
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

    /** The whole log as a text file, for reading or attaching to a bug report. */
    @GetMapping("/jobs/{id}/log.txt")
    public ResponseEntity<StreamingResponseBody> logFile(@PathVariable long id) {
        JobResponse job = job(id);
        DateTimeFormatter time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);
        StreamingResponseBody body = out -> {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.println("Import " + job.id() + ": " + job.kind() + " " + job.sourcePath() + " from "
                    + job.baseUrl() + ", " + job.status());
            jobs.eachLogLine(id, line -> writer.println(time.format(line.at()) + " " + line.level() + " "
                    + line.message()));
            writer.flush();
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-" + id + ".log.txt\"")
                .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                .body(body);
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
        return service.retry(user, id, request == null ? null : request.token());
    }
}
