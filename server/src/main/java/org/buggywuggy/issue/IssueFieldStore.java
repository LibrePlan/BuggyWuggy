/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Multi-valued system fields of an issue: labels, components and versions. */
@Component
public class IssueFieldStore {

    public enum VersionKind { AFFECTS, FIX }

    /** Id and name of a referenced component or version. */
    public record Named(long id, String name) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public IssueFieldStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- labels ----------------------------------------------------------------------

    public List<String> labels(long issueId) {
        return jdbc.queryForList("select label from issue_label where issue_id = :issue order by label",
                Map.of("issue", issueId), String.class);
    }

    public void replaceLabels(long issueId, Collection<String> labels) {
        jdbc.update("delete from issue_label where issue_id = :issue", Map.of("issue", issueId));
        insertAll("insert into issue_label (issue_id, label) values (:issue, :value)", issueId, labels, null);
    }

    // --- components ------------------------------------------------------------------

    public List<Named> components(long issueId) {
        return jdbc.query("""
                select c.id, c.name from issue_component ic join component c on c.id = ic.component_id
                where ic.issue_id = :issue order by lower(c.name)""",
                Map.of("issue", issueId), (rs, n) -> new Named(rs.getLong(1), rs.getString(2)));
    }

    public void replaceComponents(long issueId, Collection<Long> componentIds) {
        jdbc.update("delete from issue_component where issue_id = :issue", Map.of("issue", issueId));
        insertAll("insert into issue_component (issue_id, component_id) values (:issue, :value)",
                issueId, componentIds, null);
    }

    /** The subset of {@code ids} that are components of the project. */
    public Set<Long> componentsOfProject(long projectId, Collection<Long> ids) {
        return idsInProject("component", projectId, ids);
    }

    // --- versions --------------------------------------------------------------------

    public List<Named> versions(long issueId, VersionKind kind) {
        return jdbc.query("""
                select v.id, v.name from issue_version iv join project_version v on v.id = iv.version_id
                where iv.issue_id = :issue and iv.kind = :kind order by v.position, v.id""",
                Map.of("issue", issueId, "kind", kind.name()), (rs, n) -> new Named(rs.getLong(1), rs.getString(2)));
    }

    public void replaceVersions(long issueId, VersionKind kind, Collection<Long> versionIds) {
        jdbc.update("delete from issue_version where issue_id = :issue and kind = :kind",
                Map.of("issue", issueId, "kind", kind.name()));
        insertAll("insert into issue_version (issue_id, version_id, kind) values (:issue, :value, :kind)",
                issueId, versionIds, kind.name());
    }

    /** The subset of {@code ids} that are versions of the project. */
    public Set<Long> versionsOfProject(long projectId, Collection<Long> ids) {
        return idsInProject("project_version", projectId, ids);
    }

    // --- watchers --------------------------------------------------------------------

    public void addWatcher(long issueId, long userId, java.time.Instant at) {
        jdbc.update("""
                insert into watcher (issue_id, user_id, created_at) values (:issue, :user, :at)
                on conflict do nothing""",
                new MapSqlParameterSource().addValue("issue", issueId).addValue("user", userId)
                        .addValue("at", java.sql.Timestamp.from(at)));
    }

    public void removeWatcher(long issueId, long userId) {
        jdbc.update("delete from watcher where issue_id = :issue and user_id = :user",
                Map.of("issue", issueId, "user", userId));
    }

    public boolean isWatching(long issueId, long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from watcher where issue_id = :issue and user_id = :user)",
                Map.of("issue", issueId, "user", userId), Boolean.class));
    }

    // --- timestamps ------------------------------------------------------------------

    /**
     * Sets {@code updated_at} of an issue without changing its version, for activity around the
     * issue (comments, links) that must not make concurrent edits of the issue fail.
     */
    public void touch(long issueId, java.time.Instant at) {
        jdbc.update("update issue set updated_at = greatest(updated_at, :at) where id = :issue",
                new MapSqlParameterSource().addValue("issue", issueId).addValue("at", java.sql.Timestamp.from(at)));
    }

    // --- helpers ---------------------------------------------------------------------

    private Set<Long> idsInProject(String table, long projectId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "select id from " + table + " where project_id = :project and id in (:ids)",
                Map.of("project", projectId, "ids", ids), Long.class));
    }

    private void insertAll(String sql, long issueId, Collection<?> values, String kind) {
        MapSqlParameterSource[] batch = values.stream()
                .map(v -> new MapSqlParameterSource().addValue("issue", issueId).addValue("value", v)
                        .addValue("kind", kind))
                .toArray(MapSqlParameterSource[]::new);
        if (batch.length > 0) {
            jdbc.batchUpdate(sql, batch);
        }
    }
}
