/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.buggywuggy.attachment.AttachmentFiles;
import org.buggywuggy.attachment.AttachmentService;
import org.buggywuggy.attachment.BlobStore;
import org.buggywuggy.attachment.BlobStore.StoredBlob;
import org.buggywuggy.issue.IssueFieldStore;
import org.buggywuggy.issue.IssueFieldStore.VersionKind;
import org.buggywuggy.issue.IssueHistory;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.workflow.WorkflowService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes imported data with its original authors and times, for every kind of source.
 * Nothing written here sends notifications: it goes around the services that do. Callers
 * run each issue in its own transaction.
 */
@Component
public class ImportWriter {

    /** Kinds of {@code external_ref} rows. */
    public static final class Ref {
        public static final String USER = "user";
        public static final String ISSUE = "issue";
        public static final String COMMENT = "comment";
        public static final String ATTACHMENT = "attachment";
        public static final String VERSION = "version";

        private Ref() {
        }
    }

    /** A person in the source. {@code email} may be null or a no-reply address. */
    public record SourceUser(String externalId, String login, String name, String email) {
    }

    public record NewIssue(long projectId, long issueTypeId, long statusId, Long priorityId, Long resolutionId,
            String summary, String description, Long reporterId, Long assigneeId, Long creatorId, LocalDate dueDate,
            Instant createdAt, Instant updatedAt, Instant resolvedAt, long originSourceId, String originKey,
            String originUrl) {
    }

    public record CreatedIssue(long id, long number) {
    }

    /** The statuses an importer maps to, taken from the project's workflow for an issue type. */
    public record Statuses(Named open, Named reopened, Named closed) {
    }

    public record Named(long id, String name) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final IssueFieldStore fields;
    private final IssueHistory history;
    private final BlobStore blobs;
    private final AttachmentFiles files;
    private final WorkflowService workflows;

    public ImportWriter(NamedParameterJdbcTemplate jdbc, IssueFieldStore fields, IssueHistory history, BlobStore blobs,
            AttachmentFiles files, WorkflowService workflows) {
        this.jdbc = jdbc;
        this.fields = fields;
        this.history = history;
        this.blobs = blobs;
        this.files = files;
        this.workflows = workflows;
    }

    // --- external references -------------------------------------------------------------

    private static final Map<String, String> TABLES = Map.of(Ref.USER, "user_account", Ref.ISSUE, "issue",
            Ref.COMMENT, "comment", Ref.ATTACHMENT, "attachment", Ref.VERSION, "project_version");

    /**
     * What an earlier import created for this object, if it still exists: after a project
     * was deleted, its issues can be imported again.
     */
    public Optional<Long> localId(long sourceId, String type, String externalId) {
        String table = TABLES.get(type);
        if (table == null) {
            throw new IllegalArgumentException(type);
        }
        return jdbc.queryForList("""
                select r.local_id from external_ref r join %s t on t.id = r.local_id
                where r.import_source_id = :source and r.source_type = :type and r.source_id = :id""".formatted(table),
                Map.of("source", sourceId, "type", type, "id", externalId), Long.class).stream().findFirst();
    }

    public void remember(long sourceId, String type, String externalId, long localId, Instant at) {
        jdbc.update("""
                insert into external_ref (import_source_id, source_type, source_id, local_type, local_id, imported_at)
                values (:source, :type, :id, :type, :local, :at)
                on conflict (import_source_id, source_type, source_id) do update set local_id = excluded.local_id""",
                new MapSqlParameterSource().addValue("source", sourceId).addValue("type", type)
                        .addValue("id", externalId).addValue("local", localId).addValue("at", Timestamp.from(at)));
    }

    /**
     * The issue imported as {@code originKey} (e.g. owner/repo#12) from project {@code path} of
     * a source, by this or an earlier import; {@code name} is its key. Used for references and
     * links between issues.
     */
    public Optional<Named> issueBySource(String kind, String baseUrl, String path, String originKey) {
        return jdbc.query("""
                select i.id, p.key || '-' || i.number from import_source s
                join issue i on i.origin_source_id = s.id and lower(i.origin_key) = lower(:originKey)
                join project p on p.id = i.project_id
                where s.kind = :kind and lower(s.base_url) = lower(:url) and lower(s.project_path) = lower(:path)""",
                Map.of("kind", kind, "url", baseUrl, "path", path, "originKey", originKey),
                (rs, n) -> new Named(rs.getLong(1), rs.getString(2))).stream().findFirst();
    }

    // --- users ---------------------------------------------------------------------------

    public record ResolvedUser(long id, boolean created) {
    }

    /**
     * The local account for a person in the source: an earlier mapping, the administrator's
     * choice, an account with the same e-mail address, one with the same username, or a new
     * placeholder (inactive, without password or e-mail).
     */
    public ResolvedUser user(long sourceId, SourceUser user, Long chosen, boolean matchUsername, String host,
            Instant now) {
        if (chosen != null) {
            remember(sourceId, Ref.USER, user.externalId(), chosen, now);
            return new ResolvedUser(chosen, false);
        }
        Optional<Long> known = localId(sourceId, Ref.USER, user.externalId());
        if (known.isPresent()) {
            return new ResolvedUser(known.get(), false);
        }
        Optional<Long> match = suggest(user, matchUsername);
        if (match.isPresent()) {
            remember(sourceId, Ref.USER, user.externalId(), match.get(), now);
            return new ResolvedUser(match.get(), false);
        }
        long id = jdbc.queryForObject("""
                insert into user_account (username, display_name, active, placeholder, created_at)
                values (:username, :name, false, true, :now) returning id""",
                new MapSqlParameterSource().addValue("username", freeUsername(user.login(), host))
                        .addValue("name", user.name() == null || user.name().isBlank() ? user.login() : user.name())
                        .addValue("now", Timestamp.from(now)), Long.class);
        remember(sourceId, Ref.USER, user.externalId(), id, now);
        return new ResolvedUser(id, true);
    }

    /** An existing account that is probably the same person. */
    public Optional<Long> suggest(SourceUser user, boolean matchUsername) {
        if (user.email() != null && !user.email().isBlank()) {
            Optional<Long> byEmail = jdbc.queryForList(
                    "select id from user_account where lower(email) = lower(:email)",
                    Map.of("email", user.email()), Long.class).stream().findFirst();
            if (byEmail.isPresent()) {
                return byEmail;
            }
        }
        if (matchUsername) {
            return jdbc.queryForList("select id from user_account where lower(username) = lower(:name)",
                    Map.of("name", user.login()), Long.class).stream().findFirst();
        }
        return Optional.empty();
    }

    /** {@code login}, else {@code login@host}, else with a number added. */
    private String freeUsername(String login, String host) {
        String base = login.replaceAll("[^\\p{L}\\p{N}._@+-]", "-");
        if (base.length() < 2) {
            base = base + "-user";
        }
        List<String> candidates = new ArrayList<>(List.of(base, base + "@" + host));
        for (int i = 2; i < 1000; i++) {
            candidates.add(base + "@" + host + "-" + i);
        }
        for (String candidate : candidates) {
            String name = candidate.length() > 100 ? candidate.substring(0, 100) : candidate;
            boolean taken = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists (select 1 from user_account where lower(username) = lower(:name))",
                    Map.of("name", name), Boolean.class));
            if (!taken) {
                return name;
            }
        }
        throw new IllegalStateException("No free username for " + login);
    }

    // --- lookups -------------------------------------------------------------------------

    public Statuses statuses(long projectId, long issueTypeId) {
        long scheme = jdbc.queryForObject("select workflow_scheme_id from project where id = :id",
                Map.of("id", projectId), Long.class);
        long workflow = workflows.workflowFor(scheme, issueTypeId);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.id, s.name, s.category, ws.initial from workflow_status ws
                join status s on s.id = ws.status_id
                where ws.workflow_id = :workflow order by s.position, s.id""", Map.of("workflow", workflow));
        Named open = null;
        Named reopened = null;
        Named closed = null;
        Named anyDone = null;
        for (Map<String, Object> row : rows) {
            Named status = new Named(((Number) row.get("id")).longValue(), (String) row.get("name"));
            String name = status.name().toLowerCase(Locale.ROOT);
            if (Boolean.TRUE.equals(row.get("initial"))) {
                open = status;
            }
            if (name.equals("reopened")) {
                reopened = status;
            }
            if ("DONE".equals(row.get("category"))) {
                anyDone = status;
                if (name.equals("closed")) {
                    closed = status;
                }
            }
        }
        if (open == null || anyDone == null) {
            throw new IllegalStateException("The workflow of the project has no initial or no done status.");
        }
        return new Statuses(open, reopened == null ? open : reopened, closed == null ? anyDone : closed);
    }

    public Named defaultResolution() {
        return jdbc.queryForObject("""
                select id, name from resolution order by is_default desc, position limit 1""", Map.of(),
                (rs, n) -> new Named(rs.getLong(1), rs.getString(2)));
    }

    public Optional<Named> defaultPriority() {
        return jdbc.query("select id, name from priority where is_default", Map.of(),
                (rs, n) -> new Named(rs.getLong(1), rs.getString(2))).stream().findFirst();
    }

    public Map<Long, String> names(String table) {
        if (!List.of("issue_type", "priority", "status", "resolution").contains(table)) {
            throw new IllegalArgumentException(table);
        }
        Map<Long, String> names = new java.util.HashMap<>();
        jdbc.query("select id, name from " + table, Map.of(), rs -> {
            names.put(rs.getLong(1), rs.getString(2));
        });
        return names;
    }

    public String userName(long userId) {
        return jdbc.queryForObject("select display_name from user_account where id = :id", Map.of("id", userId),
                String.class);
    }

    public String projectKey(long projectId) {
        return jdbc.queryForObject("select key from project where id = :id", Map.of("id", projectId), String.class);
    }

    // --- issues --------------------------------------------------------------------------

    public CreatedIssue createIssue(NewIssue issue) {
        long number = jdbc.queryForObject(
                "update project set issue_counter = issue_counter + 1 where id = :id returning issue_counter",
                Map.of("id", issue.projectId()), Long.class);
        long id = jdbc.queryForObject("""
                insert into issue (project_id, number, issue_type_id, status_id, priority_id, resolution_id, summary,
                                   description, reporter_id, assignee_id, creator_id, due_date, created_at, updated_at,
                                   resolved_at, origin_source_id, origin_key, origin_url)
                values (:project, :number, :type, :status, :priority, :resolution, :summary, :description, :reporter,
                        :assignee, :creator, :due, :created, :updated, :resolved, :source, :originKey, :originUrl)
                returning id""",
                new MapSqlParameterSource().addValue("project", issue.projectId()).addValue("number", number)
                        .addValue("type", issue.issueTypeId()).addValue("status", issue.statusId())
                        .addValue("priority", issue.priorityId()).addValue("resolution", issue.resolutionId())
                        .addValue("summary", issue.summary()).addValue("description", issue.description())
                        .addValue("reporter", issue.reporterId()).addValue("assignee", issue.assigneeId())
                        .addValue("creator", issue.creatorId())
                        .addValue("due", issue.dueDate() == null ? null : Date.valueOf(issue.dueDate()))
                        .addValue("created", Timestamp.from(issue.createdAt()))
                        .addValue("updated", Timestamp.from(issue.updatedAt()))
                        .addValue("resolved", issue.resolvedAt() == null ? null : Timestamp.from(issue.resolvedAt()))
                        .addValue("source", issue.originSourceId()).addValue("originKey", issue.originKey())
                        .addValue("originUrl", issue.originUrl()),
                Long.class);
        return new CreatedIssue(id, number);
    }

    /** Labels may not contain white space: it is replaced by a hyphen. */
    public static String label(String name) {
        return name.strip().replaceAll("\\s+", "-");
    }

    public void labels(long issueId, Collection<String> labels) {
        fields.replaceLabels(issueId, new java.util.TreeSet<>(labels));
    }

    /** Adds the issue to these components of the project, creating those that do not exist. */
    public void components(long projectId, long issueId, Collection<String> names) {
        for (String name : new java.util.LinkedHashSet<>(names)) {
            if (name.isBlank()) {
                continue;
            }
            long componentId = jdbc.queryForList("select id from component where project_id = :project "
                    + "and lower(name) = lower(:name)", Map.of("project", projectId, "name", name), Long.class)
                    .stream().findFirst().orElseGet(() -> jdbc.queryForObject("""
                            insert into component (project_id, name) values (:project, :name) returning id""",
                            Map.of("project", projectId, "name", name), Long.class));
            jdbc.update("""
                    insert into issue_component (issue_id, component_id) values (:issue, :component)
                    on conflict do nothing""", Map.of("issue", issueId, "component", componentId));
        }
    }

    public void fixVersions(long issueId, Collection<Long> versionIds) {
        fields.replaceVersions(issueId, VersionKind.FIX, versionIds);
    }

    /** The project version with this name, created when missing. */
    public long version(long projectId, String name, String description, LocalDate releaseDate, boolean released) {
        Optional<Long> existing = jdbc.queryForList(
                "select id from project_version where project_id = :project and lower(name) = lower(:name)",
                Map.of("project", projectId, "name", name), Long.class).stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        return jdbc.queryForObject("""
                insert into project_version (project_id, name, description, release_date, released, position)
                values (:project, :name, :description, :release, :released,
                        (select coalesce(max(position), 0) + 1 from project_version where project_id = :project))
                returning id""",
                new MapSqlParameterSource().addValue("project", projectId).addValue("name", name)
                        .addValue("description", description == null || description.isBlank() ? null : description)
                        .addValue("release", releaseDate == null ? null : Date.valueOf(releaseDate))
                        .addValue("released", released), Long.class);
    }

    public void watch(long issueId, long userId, Instant at) {
        fields.addWatcher(issueId, userId, at);
    }

    public void history(long issueId, Long authorId, Instant at, Changes changes) {
        history.record(issueId, authorId, at, changes);
    }

    public long comment(long issueId, Long authorId, String body, Instant createdAt, Instant updatedAt) {
        return jdbc.queryForObject("""
                insert into comment (issue_id, author_id, body, created_at, updated_at, update_author_id)
                values (:issue, :author, :body, :created, :updated, :updateAuthor) returning id""",
                new MapSqlParameterSource().addValue("issue", issueId).addValue("author", authorId)
                        .addValue("body", body == null || body.isBlank() ? "—" : body)
                        .addValue("created", Timestamp.from(createdAt)).addValue("updated", Timestamp.from(updatedAt))
                        .addValue("updateAuthor", updatedAt.equals(createdAt) ? null : authorId),
                Long.class);
    }

    /** Stores a file; it is removed again if the transaction rolls back. */
    public long attachment(long issueId, Long authorId, String fileName, String contentType, Instant at,
            InputStream content) {
        StoredBlob blob;
        try (InputStream in = content) {
            blob = blobs.put(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Storing " + fileName + " failed: " + e.getMessage(), e);
        }
        files.deleteOnRollback(List.of(blob.key()));
        String name = AttachmentService.safeFileName(fileName);
        long id = jdbc.queryForObject("""
                insert into attachment (issue_id, author_id, file_name, media_type, size_bytes, sha256, storage_key,
                                        created_at)
                values (:issue, :author, :name, :type, :size, :sha256, :key, :at) returning id""",
                new MapSqlParameterSource().addValue("issue", issueId).addValue("author", authorId)
                        .addValue("name", name).addValue("type", AttachmentService.mediaType(name, contentType))
                        .addValue("size", blob.size()).addValue("sha256", blob.sha256()).addValue("key", blob.key())
                        .addValue("at", Timestamp.from(at)), Long.class);
        Changes changes = new Changes();
        changes.record(IssueHistory.Fields.ATTACHMENT, null, null, id, name);
        history.record(issueId, authorId, at, changes);
        return id;
    }

    public boolean remoteLink(long issueId, String url, String title, String relation, Instant at) {
        return jdbc.update("""
                insert into issue_remote_link (issue_id, url, title, relation, created_at)
                values (:issue, :url, :title, :relation, :at) on conflict do nothing""",
                new MapSqlParameterSource().addValue("issue", issueId).addValue("url", url).addValue("title", title)
                        .addValue("relation", relation).addValue("at", Timestamp.from(at))) > 0;
    }

    /** Links {@code blocker} → blocks → {@code blocked}, with history on both issues. */
    public void blocks(long blockerId, String blockerKey, long blockedId, String blockedKey, Long authorId,
            Instant at) {
        link("Blocks", blockerId, blockerKey, blockedId, blockedKey, authorId, at);
    }

    /**
     * Links {@code source} → {@code target} with the link type of that name (falling back to
     * Relates), with history on both issues. Does nothing when the link exists.
     */
    public void link(String typeName, long sourceId, String sourceKey, long targetId, String targetKey,
            Long authorId, Instant at) {
        Map<String, Object> type = linkType(typeName);
        int added = jdbc.update("""
                insert into issue_link (link_type_id, source_issue_id, target_issue_id, created_at)
                values (:type, :source, :target, :at) on conflict do nothing""",
                new MapSqlParameterSource().addValue("type", type.get("id")).addValue("source", sourceId)
                        .addValue("target", targetId).addValue("at", Timestamp.from(at)));
        if (added == 0) {
            return;
        }
        Changes onSource = new Changes();
        onSource.record(IssueHistory.Fields.LINK, null, null, targetKey, type.get("outward_description") + " " + targetKey);
        history.record(sourceId, authorId, at, onSource);
        Changes onTarget = new Changes();
        onTarget.record(IssueHistory.Fields.LINK, null, null, sourceKey, type.get("inward_description") + " " + sourceKey);
        history.record(targetId, authorId, at, onTarget);
    }

    /** The link type of that name, or Relates. */
    private Map<String, Object> linkType(String typeName) {
        return jdbc.queryForList("""
                select id, outward_description, inward_description from issue_link_type
                where lower(name) in (lower(:name), 'relates') order by lower(name) = lower(:name) desc limit 1""",
                Map.of("name", typeName)).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No link type " + typeName));
    }

    /** Whether two issues are linked, in either direction, with the link type of that name (or Relates). */
    public boolean linked(long a, long b, String typeName) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from issue_link where link_type_id = :type
                    and ((source_issue_id = :a and target_issue_id = :b) or (source_issue_id = :b and target_issue_id = :a)))""",
                Map.of("a", a, "b", b, "type", linkType(typeName).get("id")), Boolean.class));
    }

    /** What the hierarchy rules need to know about an issue. */
    public record Hierarchy(long id, long projectId, long typeId, String typeName, int level, long statusId,
            Long parentId, boolean hasChildren) {
    }

    public Optional<Hierarchy> hierarchy(long issueId) {
        return jdbc.query("""
                select i.id, i.project_id, t.id, t.name, t.hierarchy_level, i.status_id, i.parent_id,
                       exists (select 1 from issue c where c.parent_id = i.id)
                from issue i join issue_type t on t.id = i.issue_type_id where i.id = :id""",
                Map.of("id", issueId), (rs, n) -> new Hierarchy(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getString(4), rs.getInt(5), rs.getLong(6), (Long) rs.getObject(7), rs.getBoolean(8)))
                .stream().findFirst();
    }

    /** The first sub-task type, if there is one. */
    public Optional<Named> subtaskType() {
        return jdbc.query("select id, name from issue_type where hierarchy_level = -1 order by position, id limit 1",
                Map.of(), (rs, n) -> new Named(rs.getLong(1), rs.getString(2))).stream().findFirst();
    }

    /** Whether the workflow of the project for {@code typeId} has {@code statusId}. */
    public boolean workflowHas(long projectId, long typeId, long statusId) {
        long scheme = jdbc.queryForObject("select workflow_scheme_id from project where id = :id",
                Map.of("id", projectId), Long.class);
        return workflows.containsStatus(workflows.workflowFor(scheme, typeId), statusId);
    }

    /**
     * Makes {@code child} a child of {@code parent}, changing its type to {@code newType} when
     * not null; with history on the child. The caller checks the hierarchy rules.
     */
    public void parent(Hierarchy child, long parentId, String parentKey, Named newType, Long authorId, Instant at) {
        jdbc.update("update issue set parent_id = :parent, issue_type_id = :type where id = :id",
                Map.of("id", child.id(), "parent", parentId, "type", newType == null ? child.typeId() : newType.id()));
        Changes changes = new Changes();
        if (newType != null) {
            changes.record(IssueHistory.Fields.ISSUE_TYPE, child.typeId(), child.typeName(), newType.id(),
                    newType.name());
        }
        changes.record(IssueHistory.Fields.PARENT, null, null, parentId, parentKey);
        history.record(child.id(), authorId, at, changes);
    }

    /** A resolution by name (case-insensitive), else the default one. */
    public Named resolution(String name) {
        return jdbc.query("select id, name from resolution where lower(name) = lower(:name)", Map.of("name", name),
                (rs, n) -> new Named(rs.getLong(1), rs.getString(2))).stream().findFirst()
                .orElseGet(this::defaultResolution);
    }

    public void description(long issueId, String description) {
        jdbc.update("update issue set description = :text where id = :id",
                Map.of("id", issueId, "text", description));
    }

    public void commentBody(long commentId, String body) {
        jdbc.update("update comment set body = :text where id = :id", Map.of("id", commentId, "text", body));
    }
}
