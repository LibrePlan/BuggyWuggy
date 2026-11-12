/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.buggywuggy.common.ApiNullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Import jobs and their log. Every method commits on its own, so progress is visible while
 * a job runs and is kept when it fails.
 */
@Component
public class ImportJobs {

    public enum Status { RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED }

    public enum Level { INFO, WARN, ERROR }

    public record Counters(int issuesTotal, int issuesDone, int issuesImported, int comments, int attachments,
            int usersCreated, int warnings) {
    }

    public record JobResponse(long id, String kind, String baseUrl, String sourcePath, @ApiNullable Long projectId,
            @ApiNullable String projectKey, @ApiNullable String projectName, Status status,
            @ApiNullable String createdBy, Instant createdAt, @ApiNullable Instant finishedAt, Counters counters,
            @ApiNullable String message, boolean credentialsAvailable) {
    }

    public record LogLine(long id, Instant at, Level level, String message) {
    }

    /** What a job needs to run again: its source and options, without credentials. */
    public record Definition(long id, long sourceId, String kind, String baseUrl, String sourcePath, Long projectId,
            String options, Status status) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public ImportJobs(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Jobs that were running when BuggyWuggy stopped can never finish. */
    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        jdbc.update("""
                update import_job set status = 'INTERRUPTED', finished_at = :now,
                       message = 'BuggyWuggy was stopped while the import ran.'
                where status = 'RUNNING'""", Map.of("now", Timestamp.from(clock.instant())));
    }

    public long sourceId(String kind, String baseUrl, String path) {
        Map<String, Object> params = Map.of("kind", kind, "url", baseUrl, "path", path);
        return jdbc.queryForList("""
                select id from import_source
                where kind = :kind and lower(coalesce(base_url, '')) = lower(:url)
                  and lower(coalesce(project_path, '')) = lower(:path)""", params, Long.class)
                .stream().findFirst()
                .orElseGet(() -> jdbc.queryForObject("""
                        insert into import_source (kind, base_url, project_path, label, created_at)
                        values (:kind, :url, :path, :label, :now) returning id""",
                        new MapSqlParameterSource(params).addValue("label", path + " (" + baseUrl + ")")
                                .addValue("now", Timestamp.from(clock.instant())), Long.class));
    }

    public long create(long sourceId, long projectId, String options, long userId) {
        return jdbc.queryForObject("""
                insert into import_job (import_source_id, project_id, status, options, created_by, created_at)
                values (:source, :project, 'RUNNING', :options, :user, :now) returning id""",
                new MapSqlParameterSource().addValue("source", sourceId).addValue("project", projectId)
                        .addValue("options", options).addValue("user", userId)
                        .addValue("now", Timestamp.from(clock.instant())), Long.class);
    }

    public boolean anyRunning() {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from import_job where status = 'RUNNING')", Map.of(), Boolean.class));
    }

    public void total(long jobId, int total) {
        jdbc.update("update import_job set issues_total = :total where id = :id", Map.of("id", jobId, "total", total));
    }

    /** Adds to the counters of a job. */
    public void count(long jobId, int done, int imported, int comments, int attachments, int users) {
        jdbc.update("""
                update import_job set issues_done = issues_done + :done, issues_imported = issues_imported + :imported,
                       comments = comments + :comments, attachments = attachments + :attachments,
                       users_created = users_created + :users
                where id = :id""",
                new MapSqlParameterSource().addValue("id", jobId).addValue("done", done).addValue("imported", imported)
                        .addValue("comments", comments).addValue("attachments", attachments).addValue("users", users));
    }

    public void log(long jobId, Level level, String message) {
        jdbc.update("insert into import_job_log (job_id, at, level, message) values (:job, :at, :level, :message)",
                new MapSqlParameterSource().addValue("job", jobId).addValue("at", Timestamp.from(clock.instant()))
                        .addValue("level", level.name()).addValue("message", message));
        if (level != Level.INFO) {
            jdbc.update("update import_job set warnings = warnings + 1 where id = :id", Map.of("id", jobId));
        }
    }

    public void finish(long jobId, Status status, String message) {
        jdbc.update("""
                update import_job set status = :status, finished_at = :now, message = :message
                where id = :id and status = 'RUNNING'""",
                new MapSqlParameterSource().addValue("id", jobId).addValue("status", status.name())
                        .addValue("now", Timestamp.from(clock.instant())).addValue("message", message));
    }

    public Optional<Definition> definition(long jobId) {
        return jdbc.query("""
                select j.id, j.import_source_id, s.kind, s.base_url, s.project_path, j.project_id, j.options, j.status
                from import_job j join import_source s on s.id = j.import_source_id where j.id = :id""",
                Map.of("id", jobId), (rs, n) -> new Definition(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), (Long) rs.getObject(6), rs.getString(7),
                        Status.valueOf(rs.getString(8))))
                .stream().findFirst();
    }

    private static final String SELECT = """
            select j.*, s.kind, s.base_url, s.project_path, p.key as project_key, p.name as project_name,
                   u.display_name as created_by_name
            from import_job j
            join import_source s on s.id = j.import_source_id
            left join project p on p.id = j.project_id
            left join user_account u on u.id = j.created_by
            """;

    public List<JobResponse> list(java.util.function.LongPredicate credentials) {
        return jdbc.query(SELECT + " order by j.created_at desc, j.id desc limit 100", Map.of(),
                (rs, n) -> toResponse(rs, credentials));
    }

    public Optional<JobResponse> get(long jobId, java.util.function.LongPredicate credentials) {
        return jdbc.query(SELECT + " where j.id = :id", Map.of("id", jobId), (rs, n) -> toResponse(rs, credentials))
                .stream().findFirst();
    }

    public List<LogLine> log(long jobId, long afterId) {
        return jdbc.query("""
                select id, at, level, message from import_job_log
                where job_id = :job and id > :after order by id limit 1000""",
                Map.of("job", jobId, "after", afterId),
                (rs, n) -> new LogLine(rs.getLong(1), rs.getTimestamp(2).toInstant(), Level.valueOf(rs.getString(3)),
                        rs.getString(4)));
    }

    private static JobResponse toResponse(ResultSet rs, java.util.function.LongPredicate credentials)
            throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        long id = rs.getLong("id");
        return new JobResponse(id, rs.getString("kind"), rs.getString("base_url"), rs.getString("project_path"),
                (Long) rs.getObject("project_id"), rs.getString("project_key"), rs.getString("project_name"),
                Status.valueOf(rs.getString("status")), rs.getString("created_by_name"),
                rs.getTimestamp("created_at").toInstant(), finished == null ? null : finished.toInstant(),
                new Counters(rs.getInt("issues_total"), rs.getInt("issues_done"), rs.getInt("issues_imported"),
                        rs.getInt("comments"), rs.getInt("attachments"), rs.getInt("users_created"),
                        rs.getInt("warnings")),
                rs.getString("message"), credentials.test(id));
    }
}
