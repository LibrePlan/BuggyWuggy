/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.attachment;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.forbidden;
import static org.buggywuggy.common.ApiException.notFound;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.buggywuggy.attachment.AttachmentDtos.AttachmentResponse;
import org.buggywuggy.attachment.BlobStore.StoredBlob;
import org.buggywuggy.issue.IssueFieldStore;
import org.buggywuggy.issue.IssueHistory;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;
import org.buggywuggy.issue.IssueService;
import org.buggywuggy.issue.IssueService.IssueAccess;
import org.buggywuggy.project.ProjectAccess;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserDtos.UserRef;
import org.springframework.core.io.Resource;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachments of issues. Adding and deleting attachments is recorded in the issue history (as
 * Jira does), and counts as an update of the issue without changing its version.
 */
@Service
@Transactional
public class AttachmentService {

    /** Image types browsers render safely; everything else is served as a download. */
    static final Set<String> PREVIEWABLE = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    static final int MAX_FILES_PER_UPLOAD = 20;

    private static final String SELECT = """
            select a.id, a.issue_id, a.file_name, a.media_type, a.size_bytes, a.storage_key, a.created_at,
                   a.author_id, u.username, u.display_name
            from attachment a left join user_account u on u.id = a.author_id
            """;

    /** An attachment's file, with its description. */
    public record Download(AttachmentResponse attachment, Resource content) {
    }

    private record Row(AttachmentResponse response, long issueId, String storageKey) {
    }

    private final IssueService issues;
    private final IssueFieldStore fields;
    private final IssueHistory history;
    private final BlobStore blobs;
    private final AttachmentFiles files;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public AttachmentService(IssueService issues, IssueFieldStore fields, IssueHistory history, BlobStore blobs,
            AttachmentFiles files, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.issues = issues;
        this.fields = fields;
        this.history = history;
        this.blobs = blobs;
        this.files = files;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Attachments of an issue, oldest first. */
    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(BuggyWuggyPrincipal user, String issueIdOrKey) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        return jdbc.query(SELECT + " where a.issue_id = :issue order by a.created_at, a.id",
                Map.of("issue", access.issue().getId()), (rs, n) -> toRow(rs, access.project()).response());
    }

    public List<AttachmentResponse> upload(BuggyWuggyPrincipal user, String issueIdOrKey, List<MultipartFile> uploads) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        access.project().require(ProjectPermission.CREATE_ATTACHMENT);
        if (access.project().project().isArchived()) {
            throw org.buggywuggy.common.ApiException.conflict("project.archived",
                    access.project().project().getProjectKey());
        }
        if (uploads == null || uploads.isEmpty()) {
            throw badRequest("attachment.no-files");
        }
        if (uploads.size() > MAX_FILES_PER_UPLOAD) {
            throw badRequest("attachment.too-many-files", MAX_FILES_PER_UPLOAD);
        }
        long issueId = access.issue().getId();
        Instant now = clock.instant();
        List<String> storedKeys = new ArrayList<>();
        files.deleteOnRollback(storedKeys);
        Changes changes = new Changes();
        List<Long> ids = new ArrayList<>();
        for (MultipartFile upload : uploads) {
            String fileName = safeFileName(upload.getOriginalFilename());
            StoredBlob blob;
            try (InputStream in = upload.getInputStream()) {
                blob = blobs.put(in);
            } catch (IOException e) {
                throw new UncheckedIOException("Storing attachment failed", e);
            }
            storedKeys.add(blob.key());
            Long id = jdbc.queryForObject("""
                    insert into attachment (issue_id, author_id, file_name, media_type, size_bytes, sha256,
                                            storage_key, created_at)
                    values (:issue, :author, :name, :type, :size, :sha256, :key, :now) returning id""",
                    new MapSqlParameterSource().addValue("issue", issueId).addValue("author", user.id())
                            .addValue("name", fileName).addValue("type", mediaType(fileName, upload.getContentType()))
                            .addValue("size", blob.size()).addValue("sha256", blob.sha256())
                            .addValue("key", blob.key()).addValue("now", Timestamp.from(now)),
                    Long.class);
            ids.add(id);
            changes.record(Fields.ATTACHMENT, null, null, id, fileName);
        }
        history.save(issueId, user.id(), now, changes);
        fields.touch(issueId, now);
        return jdbc.query(SELECT + " where a.id in (:ids) order by a.id", Map.of("ids", ids),
                (rs, n) -> toRow(rs, access.project()).response());
    }

    @Transactional(readOnly = true)
    public Download download(BuggyWuggyPrincipal user, long attachmentId) {
        Row row = find(user, attachmentId);
        Resource content = blobs.get(row.storageKey());
        if (!content.exists()) {
            throw notFound("attachment.file-missing", row.response().fileName());
        }
        return new Download(row.response(), content);
    }

    public void delete(BuggyWuggyPrincipal user, String issueIdOrKey, long attachmentId) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        Row row = jdbc.query(SELECT + " where a.id = :id and a.issue_id = :issue",
                        Map.of("id", attachmentId, "issue", access.issue().getId()),
                        (rs, n) -> toRow(rs, access.project()))
                .stream().findFirst().orElseThrow(() -> notFound("attachment.not-found", attachmentId));
        if (!row.response().canDelete()) {
            throw forbidden("attachment.delete-permission");
        }
        if (access.project().project().isArchived()) {
            throw org.buggywuggy.common.ApiException.conflict("project.archived",
                    access.project().project().getProjectKey());
        }
        jdbc.update("delete from attachment where id = :id", Map.of("id", attachmentId));
        Instant now = clock.instant();
        Changes changes = new Changes();
        changes.record(Fields.ATTACHMENT, attachmentId, row.response().fileName(), null, null);
        history.save(row.issueId(), user.id(), now, changes);
        fields.touch(row.issueId(), now);
        files.deleteAfterCommit(List.of(row.storageKey()));
    }

    private Row find(BuggyWuggyPrincipal user, long attachmentId) {
        Long issueId = jdbc.queryForList("select issue_id from attachment where id = :id",
                Map.of("id", attachmentId), Long.class).stream().findFirst()
                .orElseThrow(() -> notFound("attachment.not-found", attachmentId));
        IssueAccess access;
        try {
            access = issues.access(user, String.valueOf(issueId));
        } catch (org.buggywuggy.common.ApiException e) {
            // Do not reveal that the attachment exists.
            throw notFound("attachment.not-found", attachmentId);
        }
        return jdbc.query(SELECT + " where a.id = :id", Map.of("id", attachmentId),
                (rs, n) -> toRow(rs, access.project())).getFirst();
    }

    private static Row toRow(ResultSet rs, ProjectAccess project) throws SQLException {
        long authorId = rs.getLong("author_id");
        UserRef author = rs.wasNull() ? null
                : new UserRef(authorId, rs.getString("username"), rs.getString("display_name"));
        boolean own = author != null && author.id() == project.user().id();
        boolean canDelete = project.has(ProjectPermission.DELETE_ALL_ATTACHMENTS)
                || own && project.has(ProjectPermission.DELETE_OWN_ATTACHMENTS);
        String mediaType = rs.getString("media_type");
        return new Row(new AttachmentResponse(rs.getLong("id"), rs.getString("file_name"), mediaType,
                rs.getLong("size_bytes"), author, rs.getTimestamp("created_at").toInstant(),
                PREVIEWABLE.contains(mediaType), canDelete),
                rs.getLong("issue_id"), rs.getString("storage_key"));
    }

    /**
     * The file name without any directory part or control characters, at most 255 characters
     * (keeping the extension).
     */
    static String safeFileName(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "file";
        }
        if (name.length() > 255) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
            name = name.substring(0, 255 - ext.length()) + ext;
        }
        return name;
    }

    /**
     * The media type, determined from the file name; the browser-declared type is used only
     * when the name says nothing.
     */
    static String mediaType(String fileName, String declared) {
        return MediaTypeFactory.getMediaType(fileName).map(MediaType::toString)
                .or(() -> parse(declared))
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private static Optional<String> parse(String declared) {
        if (declared == null || declared.isBlank()) {
            return Optional.empty();
        }
        try {
            MediaType type = MediaType.parseMediaType(declared);
            return type.isConcrete() ? Optional.of(type.getType() + "/" + type.getSubtype()) : Optional.empty();
        } catch (InvalidMediaTypeException e) {
            return Optional.empty();
        }
    }
}
