/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.workflow;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read access to workflows: which workflow applies to an issue, and its statuses. */
@Service
@Transactional(readOnly = true)
public class WorkflowService {

    private final NamedParameterJdbcTemplate jdbc;

    public WorkflowService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The workflow for an issue type in a workflow scheme: the type's own entry, else the scheme default. */
    public long workflowFor(long workflowSchemeId, long issueTypeId) {
        return jdbc.queryForObject("""
                select e.workflow_id from workflow_scheme_entry e
                where e.workflow_scheme_id = :scheme and (e.issue_type_id = :type or e.issue_type_id is null)
                order by e.issue_type_id nulls last
                limit 1""", Map.of("scheme", workflowSchemeId, "type", issueTypeId), Long.class);
    }

    public long initialStatus(long workflowId) {
        return jdbc.queryForObject(
                "select status_id from workflow_status where workflow_id = :workflow and initial",
                Map.of("workflow", workflowId), Long.class);
    }

    /** A workflow transition; {@code from_status_id = null} transitions are available from every status. */
    public record Transition(long id, String name, long toStatusId) {
    }

    /** Transitions available from {@code statusId}, excluding transitions to the same status. */
    public List<Transition> availableTransitions(long workflowId, long statusId) {
        return jdbc.query("""
                select id, name, to_status_id from workflow_transition
                where workflow_id = :workflow
                  and (from_status_id = :status or from_status_id is null)
                  and to_status_id <> :status
                order by position, id""",
                Map.of("workflow", workflowId, "status", statusId),
                (rs, n) -> new Transition(rs.getLong("id"), rs.getString("name"), rs.getLong("to_status_id")));
    }

    public boolean containsStatus(long workflowId, long statusId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from workflow_status where workflow_id = :workflow and status_id = :status)""",
                Map.of("workflow", workflowId, "status", statusId), Boolean.class));
    }
}
