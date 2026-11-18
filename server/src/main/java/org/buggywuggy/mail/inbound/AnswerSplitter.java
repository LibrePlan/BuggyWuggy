/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail.inbound;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.forbidden;
import static org.buggywuggy.common.ApiException.notFound;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.buggywuggy.attachment.BlobStore;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.importer.ImportWriter;
import org.buggywuggy.issue.IssueDtos.CreateIssueRequest;
import org.buggywuggy.issue.IssueDtos.IssueResponse;
import org.buggywuggy.issue.IssueService;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes a new issue of an answer that came in by e-mail, when it raises something that deserves
 * an issue of its own. As a copy, the answer stays where it is (marked "split off into"); moved,
 * it is removed from the original issue, which takes the right to delete all comments. The new
 * issue gets the answer's sender as reporter and as e-mail contact (so that its external
 * comments go to that person too), the answer's files, and a Split link with the original.
 */
@Service
public class AnswerSplitter {

    /** {@code project} and {@code issueTypeId}: those of the original issue when not given. */
    public record SplitRequest(@NotBlank @Size(max = 255) String summary, @ApiNullable String project,
            @ApiNullable Long issueTypeId, @Size(max = 100_000) String description, boolean move) {
    }

    private final IssueService issues;
    private final ImportWriter writer;
    private final BlobStore blobs;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final jakarta.persistence.EntityManager entities;

    public AnswerSplitter(IssueService issues, ImportWriter writer, BlobStore blobs, NamedParameterJdbcTemplate jdbc,
            Clock clock, jakarta.persistence.EntityManager entities) {
        this.entities = entities;
        this.issues = issues;
        this.writer = writer;
        this.blobs = blobs;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public IssueResponse split(BuggyWuggyPrincipal user, String issueIdOrKey, long commentId, SplitRequest request) {
        IssueService.IssueAccess access = issues.access(user, issueIdOrKey);
        long issueId = access.issue().getId();
        Map<String, Object> comment = jdbc.queryForList("""
                select author_id, received, split_issue_id, mail_message_id from comment
                where id = :id and issue_id = :issue for update""", Map.of("id", commentId, "issue", issueId))
                .stream().findFirst().orElseThrow(() -> notFound("comment.not-found", commentId));
        if (!Boolean.TRUE.equals(comment.get("received"))) {
            throw badRequest("comment.not-an-answer");
        }
        if (comment.get("split_issue_id") != null) {
            throw conflict("comment.already-split");
        }
        if (request.move() && !access.project().has(ProjectPermission.DELETE_ALL_COMMENTS)) {
            throw forbidden("comment.delete-permission");
        }
        Instant now = clock.instant();
        String project = request.project() == null || request.project().isBlank()
                ? access.project().project().getProjectKey() : request.project().strip();
        long typeId = request.issueTypeId() != null ? request.issueTypeId() : access.issue().getIssueType().getId();

        // Created as any issue (permissions, workflow, notifications), then reported by the sender.
        IssueResponse created = issues.create(user, new CreateIssueRequest(project, typeId, null,
                request.summary().strip(), request.description(), null, null, null, null, null, null, null, null));
        // The rest is plain SQL: write the new issue first, and read it afresh at the end.
        entities.flush();
        Long author = comment.get("author_id") == null ? null : ((Number) comment.get("author_id")).longValue();
        if (author != null) {
            jdbc.update("update issue set reporter_id = :author where id = :id",
                    Map.of("author", author, "id", created.id()));
        }

        List<Map<String, Object>> files = jdbc.queryForList("""
                select id, file_name, media_type, storage_key, created_at from attachment where comment_id = :comment""",
                Map.of("comment", commentId));
        for (Map<String, Object> file : files) {
            if (request.move()) {
                jdbc.update("update attachment set issue_id = :issue, comment_id = null where id = :id",
                        Map.of("issue", created.id(), "id", file.get("id")));
            } else {
                Resource content = blobs.get((String) file.get("storage_key"));
                if (!content.exists()) {
                    continue;
                }
                try {
                    writer.attachment(created.id(), author, (String) file.get("file_name"),
                            (String) file.get("media_type"), ((Timestamp) file.get("created_at")).toInstant(),
                            content.getInputStream());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        jdbc.update("""
                insert into issue_mail_contact (issue_id, mailbox, address, name, subject, message_id)
                select :new, mailbox, address, name, :subject, :messageId from issue_mail_contact where issue_id = :old""",
                new MapSqlParameterSource().addValue("new", created.id()).addValue("old", issueId)
                        .addValue("subject", request.summary().strip())
                        .addValue("messageId", comment.get("mail_message_id")));
        writer.link("Split", issueId, access.key(), created.id(), created.key(), user.id(), now);

        if (request.move()) {
            jdbc.update("delete from comment where id = :id", Map.of("id", commentId));
        } else {
            jdbc.update("update comment set split_issue_id = :new where id = :id",
                    Map.of("new", created.id(), "id", commentId));
        }
        entities.clear();
        return issues.get(user, created.key());
    }
}
