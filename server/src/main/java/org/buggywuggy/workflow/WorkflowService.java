/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.workflow;

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

    public boolean containsStatus(long workflowId, long statusId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from workflow_status where workflow_id = :workflow and status_id = :status)""",
                Map.of("workflow", workflowId, "status", statusId), Boolean.class));
    }
}
