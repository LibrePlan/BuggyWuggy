/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.board;

import static org.buggywuggy.common.ApiException.badRequest;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.buggywuggy.board.BoardDtos.BoardConfigRequest;
import org.buggywuggy.board.BoardDtos.ColumnRequest;
import org.buggywuggy.board.BoardDtos.CustomStatusRequest;
import org.buggywuggy.issue.IssueDtos.StatusRef;
import org.buggywuggy.issue.IssueHistory;
import org.buggywuggy.issue.IssueHistory.Changes;
import org.buggywuggy.issue.IssueHistory.Fields;
import org.buggywuggy.issue.Status;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Maintains project-local workflow statuses within the board configuration transaction. */
@Component
class BoardStatuses {

    private final NamedParameterJdbcTemplate jdbc;
    private final IssueHistory history;
    private final Clock clock;

    BoardStatuses(NamedParameterJdbcTemplate jdbc, IssueHistory history, Clock clock) {
        this.jdbc = jdbc;
        this.history = history;
        this.clock = clock;
    }

    List<StatusRef> available(Long project) {
        return jdbc.query("""
                select id, name, category from status
                where project_id is null or project_id = :project
                order by project_id nulls first, position, id""",
                Collections.singletonMap("project", project),
                (rs, n) -> new StatusRef(rs.getLong("id"), rs.getString("name"),
                        Status.Category.valueOf(rs.getString("category"))));
    }

    List<Long> custom(long project) {
        return jdbc.queryForList("select id from status where project_id = :project order by position, id",
                Map.of("project", project), Long.class);
    }

    long lockProject(long project) {
        return jdbc.queryForObject("select workflow_scheme_id from project where id = :project for update",
                Map.of("project", project), Long.class);
    }

    BoardConfigRequest apply(long project, long scheme, long actor, BoardConfigRequest request) {
        List<Long> custom = custom(project);
        List<Long> removed = request.removedStatusIds() == null ? List.of() : request.removedStatusIds();
        Set<Long> removedIds = new HashSet<>(removed);
        if (removedIds.size() != removed.size() || !custom.containsAll(removedIds)) {
            throw badRequest("board.status-removal-confirmation");
        }

        Map<Long, StatusRef> available = new HashMap<>();
        available(project).forEach(status -> available.put(status.id(), status));
        Map<Long, Long> created = new HashMap<>();
        List<CustomStatusRequest> drafts = request.customStatuses();
        if (drafts != null) {
            Set<Long> seen = new HashSet<>();
            for (int position = 0; position < drafts.size(); position++) {
                CustomStatusRequest draft = drafts.get(position);
                long id = draft.id();
                if (!seen.add(id) || removedIds.contains(id) || (id >= 0 && !custom.contains(id))) {
                    throw badRequest("board.status-invalid", id);
                }
                String name = draft.name().strip();
                if (id < 0) {
                    scheme = ownScheme(project, scheme);
                    long status = add(project, scheme, name, position);
                    created.put(id, status);
                    available.put(status, new StatusRef(status, name, Status.Category.IN_PROGRESS));
                } else {
                    update(project, id, name, position);
                    available.put(id, new StatusRef(id, name, Status.Category.IN_PROGRESS));
                }
            }
        }

        Set<Long> mapped = new HashSet<>();
        List<ColumnRequest> columns = new ArrayList<>();
        for (ColumnRequest column : request.columns()) {
            List<Long> statusIds = new ArrayList<>();
            for (long id : column.statusIds()) {
                long status = created.getOrDefault(id, id);
                if (!available.containsKey(status) || removedIds.contains(status)) {
                    throw badRequest("board.status-invalid", id);
                }
                if (!mapped.add(status)) {
                    throw badRequest("board.status-twice", status);
                }
                statusIds.add(status);
            }
            columns.add(new ColumnRequest(column.name(), statusIds));
        }

        Map<Long, Long> replacements = request.statusReplacements() == null ? Map.of() : request.statusReplacements();
        for (long status : removed) {
            Long replacement = replacements.get(status);
            StatusRef target = replacement == null ? null : available.get(created.getOrDefault(replacement, replacement));
            remove(project, scheme, actor, available.get(status), target, mapped);
        }
        return new BoardConfigRequest(request.swimlanes(), request.resolvedDays(), columns, null, null, null);
    }

    private void checkName(long project, long status, String name) {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from status
                    where (project_id is null or project_id = :project) and id <> :status and lower(name) = lower(:name))
                """, Map.of("project", project, "status", status, "name", name), Boolean.class));
        if (exists) {
            throw badRequest("board.status-name-taken", name);
        }
    }

    private long ownScheme(long project, long scheme) {
        Map<String, Long> source = Map.of("project", project, "scheme", scheme);
        if (!jdbc.queryForList("select id from workflow_scheme where id = :scheme and project_id = :project",
                source, Long.class).isEmpty()) {
            return scheme;
        }

        long own = jdbc.queryForObject("""
                insert into workflow_scheme (name, project_id) values ('Board workflow scheme ' || :project, :project)
                returning id""", source, Long.class);
        for (long workflow : workflows(scheme)) {
            long copy = jdbc.queryForObject("""
                    insert into workflow (name, project_id) values ('Board workflow ' || :project || '/' || :workflow,
                        :project) returning id""", Map.of("project", project, "workflow", workflow), Long.class);
            Map<String, Long> params = Map.of("scheme", scheme, "own", own, "workflow", workflow, "copy", copy);
            jdbc.update("""
                    insert into workflow_status (workflow_id, status_id, initial)
                    select :copy, status_id, initial from workflow_status where workflow_id = :workflow""", params);
            jdbc.update("""
                    insert into workflow_transition (workflow_id, name, from_status_id, to_status_id, position)
                    select :copy, name, from_status_id, to_status_id, position
                    from workflow_transition where workflow_id = :workflow""", params);
            jdbc.update("""
                    insert into workflow_scheme_entry (workflow_scheme_id, issue_type_id, workflow_id)
                    select :own, issue_type_id, :copy from workflow_scheme_entry
                    where workflow_scheme_id = :scheme and workflow_id = :workflow""", params);
        }
        jdbc.update("update project set workflow_scheme_id = :own, version = version + 1 where id = :project",
                Map.of("project", project, "own", own));
        return own;
    }

    private List<Long> workflows(long scheme) {
        return jdbc.queryForList("""
                select distinct workflow_id from workflow_scheme_entry where workflow_scheme_id = :scheme
                order by workflow_id""", Map.of("scheme", scheme), Long.class);
    }

    private long add(long project, long scheme, String name, int position) {
        checkName(project, 0, name);
        long status = jdbc.queryForObject("""
                insert into status (project_id, name, category, position)
                values (:project, :name, 'IN_PROGRESS', :position) returning id""",
                Map.of("project", project, "name", name, "position", position), Long.class);
        for (long workflow : workflows(scheme)) {
            var params = Map.of("workflow", workflow, "status", status, "name", name, "position", position);
            jdbc.update("insert into workflow_status (workflow_id, status_id) values (:workflow, :status)", params);
            // A custom status is reachable from any workflow status. Give it the workflow's existing destinations.
            jdbc.update("""
                    insert into workflow_transition (workflow_id, name, from_status_id, to_status_id, position)
                    select distinct on (to_status_id) :workflow, name, :status, to_status_id, position
                    from workflow_transition where workflow_id = :workflow and from_status_id is not null
                        and to_status_id <> :status
                    order by to_status_id, position, id""", params);
            jdbc.update("""
                    insert into workflow_transition (workflow_id, name, to_status_id, position)
                    values (:workflow, :name, :status, 100 + :position)""", params);
        }
        return status;
    }

    private void update(long project, long status, String name, int position) {
        checkName(project, status, name);
        var params = Map.of("status", status, "name", name, "position", position);
        jdbc.update("update status set name = :name, position = :position where id = :status", params);
        jdbc.update("""
                update workflow_transition set name = :name, position = 100 + :position
                where to_status_id = :status and from_status_id is null""", params);
    }

    private void remove(long project, long scheme, long actor, StatusRef from, StatusRef target,
            Set<Long> mapped) {
        var source = Map.of("project", project, "status", from.id());
        // Row locks serialize reassignment with edits and transitions. The status FK also protects in-flight moves.
        List<Long> affected = jdbc.queryForList("""
                select id from issue where project_id = :project and status_id = :status order by id for update
                """, source, Long.class);
        if (!affected.isEmpty()) {
            if (target == null || target.category() == Status.Category.DONE || !mapped.contains(target.id())) {
                throw badRequest("board.status-replacement-required", from.name());
            }
            var params = Map.of("project", project, "status", from.id(), "target", target.id(), "scheme", scheme);
            boolean invalid = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists (select 1 from issue i where i.project_id = :project and i.status_id = :status
                        and not exists (
                            select 1 from workflow_status ws where ws.status_id = :target and ws.workflow_id = (
                                select e.workflow_id from workflow_scheme_entry e where e.workflow_scheme_id = :scheme
                                    and (e.issue_type_id = i.issue_type_id or e.issue_type_id is null)
                                order by e.issue_type_id nulls last limit 1)))
                    """, params, Boolean.class));
            if (invalid) {
                throw badRequest("board.status-replacement-required", from.name());
            }
            Instant now = clock.instant();
            List<Long> moved = jdbc.queryForList("""
                    update issue set status_id = :target, updated_at = :now, version = version + 1
                    where project_id = :project and status_id = :status returning id""",
                    Map.of("project", project, "status", from.id(), "target", target.id(), "now", Timestamp.from(now)),
                    Long.class);
            Changes changes = new Changes();
            changes.record(Fields.STATUS, from.id(), from.name(), target.id(), target.name());
            moved.forEach(id -> history.save(id, actor, now, changes));
        }
        jdbc.update("delete from workflow_status where status_id = :status", source);
        jdbc.update("delete from status where id = :status and project_id = :project", source);
    }
}
