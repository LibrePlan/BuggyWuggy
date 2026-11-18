/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.forbidden;
import static org.buggywuggy.common.ApiException.notFound;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.buggywuggy.issue.CollaborationDtos.CommentResponse;
import org.buggywuggy.issue.CollaborationDtos.CreateCommentRequest;
import org.buggywuggy.issue.CollaborationDtos.UpdateCommentRequest;
import org.buggywuggy.issue.IssueService.IssueAccess;
import org.buggywuggy.notification.IssueEvents;
import org.buggywuggy.project.ProjectAccess;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserDtos.UserRef;
import org.buggywuggy.mail.inbound.ExternalReplies;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments on issues. Adding a comment makes the author a watcher and counts as an update of
 * the issue ({@code updated_at}), but does not change the issue's version, so nobody's pending
 * edit of the issue fails because of a comment.
 */
@Service
@Transactional
public class CommentService {

    private final IssueEvents events;

    private static final String SELECT = """
            select c.id, c.author_id, c.body, c.created_at, c.updated_at, c.update_author_id, c.version, c.external,
                   a.username as a_username, a.display_name as a_display_name,
                   u.username as u_username, u.display_name as u_display_name
            from comment c
            left join user_account a on a.id = c.author_id
            left join user_account u on u.id = c.update_author_id
            """;

    private final IssueService issues;
    private final IssueFieldStore fields;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final ExternalReplies replies;

    public CommentService(IssueService issues, IssueFieldStore fields, NamedParameterJdbcTemplate jdbc, Clock clock,
            IssueEvents events, ExternalReplies replies) {
        this.replies = replies;
        this.events = events;
        this.issues = issues;
        this.fields = fields;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The comments of an issue, oldest first. */
    @Transactional(readOnly = true)
    public List<CommentResponse> list(BuggyWuggyPrincipal user, String issueIdOrKey) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        return jdbc.query(SELECT + " where c.issue_id = :issue order by c.created_at, c.id",
                Map.of("issue", access.issue().getId()), (rs, n) -> toResponse(rs, access.project()));
    }

    public CommentResponse create(BuggyWuggyPrincipal user, String issueIdOrKey, CreateCommentRequest request) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        access.project().require(ProjectPermission.ADD_COMMENT);
        access.requireWritable();
        long issueId = access.issue().getId();
        Instant now = clock.instant();
        boolean external = Boolean.TRUE.equals(request.external());
        ExternalReplies.Target target = null;
        if (external) {
            target = replies.target(issueId).orElseThrow(() -> badRequest("comment.no-mail-sender"));
            if (!replies.canSend()) {
                throw badRequest("comment.mail-not-configured");
            }
        }
        String body = request.body().strip();
        Long id = jdbc.queryForObject("""
                insert into comment (issue_id, author_id, body, created_at, updated_at, external)
                values (:issue, :author, :body, :now, :now, :external) returning id""",
                new MapSqlParameterSource().addValue("issue", issueId).addValue("author", user.id())
                        .addValue("body", body).addValue("now", Timestamp.from(now))
                        .addValue("external", external),
                Long.class);
        if (target != null) {
            replies.send(target, access.key(), user.displayName(), body);
        }
        fields.addWatcher(issueId, user.id(), now);
        fields.touch(issueId, now);
        events.commented(issueId, user.id(), now, id);
        return find(access, id);
    }

    public CommentResponse update(BuggyWuggyPrincipal user, String issueIdOrKey, long commentId,
            UpdateCommentRequest request) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        CommentResponse current = find(access, commentId);
        if (!current.canEdit()) {
            throw forbidden("comment.edit-permission");
        }
        access.requireWritable();
        if (request.version() != null && request.version() != current.version()) {
            throw conflict("error.conflict");
        }
        String body = request.body().strip();
        if (body.equals(current.body())) {
            return current;
        }
        int updated = jdbc.update("""
                update comment set body = :body, updated_at = :now, update_author_id = :user, version = version + 1
                where id = :id and version = :version""",
                new MapSqlParameterSource().addValue("body", body).addValue("now", Timestamp.from(clock.instant()))
                        .addValue("user", user.id()).addValue("id", commentId).addValue("version", current.version()));
        if (updated == 0) {
            throw conflict("error.conflict");
        }
        return find(access, commentId);
    }

    public void delete(BuggyWuggyPrincipal user, String issueIdOrKey, long commentId) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        CommentResponse current = find(access, commentId);
        if (!current.canDelete()) {
            throw forbidden("comment.delete-permission");
        }
        access.requireWritable();
        jdbc.update("delete from comment where id = :id", Map.of("id", commentId));
    }

    private CommentResponse find(IssueAccess access, long commentId) {
        return jdbc.query(SELECT + " where c.id = :id and c.issue_id = :issue",
                        Map.of("id", commentId, "issue", access.issue().getId()),
                        (rs, n) -> toResponse(rs, access.project()))
                .stream().findFirst()
                .orElseThrow(() -> notFound("comment.not-found", commentId));
    }

    private static CommentResponse toResponse(ResultSet rs, ProjectAccess project) throws SQLException {
        UserRef author = user(rs, "author_id", "a_");
        boolean own = author != null && author.id() == project.user().id();
        boolean canEdit = project.has(ProjectPermission.EDIT_ALL_COMMENTS)
                || own && project.has(ProjectPermission.EDIT_OWN_COMMENTS);
        boolean canDelete = project.has(ProjectPermission.DELETE_ALL_COMMENTS)
                || own && project.has(ProjectPermission.DELETE_OWN_COMMENTS);
        return new CommentResponse(rs.getLong("id"), author, rs.getString("body"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                user(rs, "update_author_id", "u_"), rs.getLong("version"), canEdit, canDelete,
                rs.getBoolean("external"));
    }

    private static UserRef user(ResultSet rs, String idColumn, String prefix) throws SQLException {
        long id = rs.getLong(idColumn);
        return rs.wasNull() ? null
                : new UserRef(id, rs.getString(prefix + "username"), rs.getString(prefix + "display_name"));
    }
}
