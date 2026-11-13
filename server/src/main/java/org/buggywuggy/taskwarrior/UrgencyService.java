/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.taskwarrior;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Calculates TaskWarrior's urgency of issues, with the coefficients of the configuration file.
 * Reads what the calculation needs for many issues at once; permissions are checked by callers.
 */
@Service
@Transactional(readOnly = true)
@EnableConfigurationProperties(UrgencyProperties.class)
public class UrgencyService {

    /** Issues read per query. */
    private static final int CHUNK = 1000;

    private static final String FACTS = """
            select i.id, i.created_at, i.due_date, p.key as project_key, pr.name as priority,
                   (i.resolution_id is not null or s.category = 'DONE') as resolved,
                   s.category = 'IN_PROGRESS' as active,
                   (select count(*) from comment c where c.issue_id = i.id) as comments,
                   (select count(*) from issue_component ic where ic.issue_id = i.id) as components,
                   array(select l.label from issue_label l where l.issue_id = i.id order by l.label) as labels,
                   exists (select 1 from issue_link k
                           join issue_link_type t on t.id = k.link_type_id and t.name = 'Blocks'
                           join issue o on o.id = k.source_issue_id
                           join status os on os.id = o.status_id
                           where k.target_issue_id = i.id and o.resolution_id is null
                             and os.category <> 'DONE') as blocked,
                   exists (select 1 from issue_link k
                           join issue_link_type t on t.id = k.link_type_id and t.name = 'Blocks'
                           join issue o on o.id = k.target_issue_id
                           join status os on os.id = o.status_id
                           where k.source_issue_id = i.id and o.resolution_id is null
                             and os.category <> 'DONE') as blocking
            from issue i
            join project p on p.id = i.project_id
            join status s on s.id = i.status_id
            left join priority pr on pr.id = i.priority_id
            where i.id in (:ids)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final UrgencyProperties properties;
    private final Clock clock;

    public UrgencyService(NamedParameterJdbcTemplate jdbc, UrgencyProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public UrgencyProperties properties() {
        return properties;
    }

    /** The urgency of each issue, by id; issues that do not exist are left out. */
    public Map<Long, Urgency.Result> of(Collection<Long> issueIds) {
        Map<Long, Urgency.Result> results = new HashMap<>();
        List<Long> ids = new ArrayList<>(issueIds);
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK));
            for (Urgency.Facts facts : jdbc.query(FACTS, Map.of("ids", chunk), (rs, n) -> facts(rs))) {
                results.put(facts.issueId(), Urgency.of(facts, properties, clock.instant(), ZoneId.systemDefault()));
            }
        }
        return results;
    }

    public Urgency.Result of(long issueId) {
        return of(List.of(issueId)).getOrDefault(issueId, new Urgency.Result(null, List.of()));
    }

    private static Urgency.Facts facts(ResultSet rs) throws SQLException {
        Array labels = rs.getArray("labels");
        List<String> names = labels == null ? List.of() : List.of((String[]) labels.getArray());
        java.sql.Date due = rs.getDate("due_date");
        return new Urgency.Facts(rs.getLong("id"), rs.getTimestamp("created_at").toInstant(),
                due == null ? null : due.toLocalDate(), rs.getBoolean("resolved"), rs.getBoolean("active"),
                rs.getString("priority"), rs.getString("project_key"), names, rs.getInt("comments"),
                rs.getInt("components"), rs.getBoolean("blocked"), rs.getBoolean("blocking"));
    }
}
