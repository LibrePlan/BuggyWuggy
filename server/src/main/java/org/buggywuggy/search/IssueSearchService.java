/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.search;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import org.buggywuggy.common.PageResponse;
import org.buggywuggy.common.SqlLike;
import org.buggywuggy.issue.IssueDtos.IssueSummaryResponse;
import org.buggywuggy.issue.IssueDtos.IssueTypeRef;
import org.buggywuggy.issue.IssueDtos.PriorityRef;
import org.buggywuggy.issue.IssueDtos.ProjectRef;
import org.buggywuggy.issue.IssueDtos.ResolutionRef;
import org.buggywuggy.issue.IssueDtos.StatusRef;
import org.buggywuggy.issue.Status;
import org.buggywuggy.project.ProjectPermissionService;
import org.buggywuggy.search.IssueQuery.Direction;
import org.buggywuggy.search.IssueQuery.Sort;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserDtos.UserRef;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue search in PostgreSQL: full-text search on the generated {@code search_vector} columns
 * of issues and comments, and plain SQL for the structured conditions. Only issues in projects
 * the user may browse are found.
 */
@Service
@Transactional(readOnly = true)
public class IssueSearchService {

    private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+");
    private static final java.util.regex.Pattern ISSUE_KEY =
            java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z0-9_]{1,9})-(\\d{1,18})$");
    private static final int MAX_TERMS = 8;

    private static final String FROM = """
            from issue i
            join project p on p.id = i.project_id
            join issue_type it on it.id = i.issue_type_id
            join status s on s.id = i.status_id
            left join priority pr on pr.id = i.priority_id
            left join resolution r on r.id = i.resolution_id
            left join user_account a on a.id = i.assignee_id
            """;

    private static final String COLUMNS = """
            select i.id, i.number, i.summary, i.created_at, i.updated_at,
                   p.id as p_id, p.key as p_key, p.name as p_name,
                   it.id as it_id, it.name as it_name, it.icon as it_icon, it.hierarchy_level as it_level,
                   s.id as s_id, s.name as s_name, s.category as s_category,
                   pr.id as pr_id, pr.name as pr_name, pr.icon as pr_icon, pr.color as pr_color,
                   r.id as r_id, r.name as r_name,
                   a.id as a_id, a.username as a_username, a.display_name as a_display_name
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public IssueSearchService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<IssueSummaryResponse> search(BuggyWuggyPrincipal user, IssueQuery query, int page, int size) {
        Conditions where = new Conditions(user);
        where.add("(" + ProjectPermissionService.PROJECT_BROWSABLE + ")");
        String rank = textCondition(query.text(), where);
        ids(where, "i.project_id", "projects", query.projectIds());
        ids(where, "i.issue_type_id", "types", query.issueTypeIds());
        ids(where, "i.status_id", "statuses", query.statusIds());
        ids(where, "i.priority_id", "priorities", query.priorityIds());
        users(where, "i.assignee_id", "assignees", query.assignees());
        users(where, "i.reporter_id", "reporters", query.reporters());
        if (query.resolution() != null) {
            where.add(query.resolution() == IssueQuery.Resolution.UNRESOLVED
                    ? "i.resolution_id is null" : "i.resolution_id is not null");
        }
        if (query.labels() != null && !query.labels().isEmpty()) {
            where.add("exists (select 1 from issue_label l where l.issue_id = i.id and l.label in (:labels))");
            where.params.addValue("labels", query.labels());
        }
        dateRange(where, "i.created_at", "created", query.createdFrom(), query.createdTo());
        dateRange(where, "i.updated_at", "updated", query.updatedFrom(), query.updatedTo());

        String whereSql = " where " + String.join("\n  and ", where.conditions);
        long total = jdbc.queryForObject("select count(*) " + FROM + whereSql, where.params, Long.class);
        where.params.addValue("limit", size).addValue("offset", (long) page * size);
        List<IssueSummaryResponse> items = jdbc.query(COLUMNS + FROM + whereSql
                        + " order by " + orderBy(query, rank) + " limit :limit offset :offset",
                where.params, (rs, n) -> toSummary(rs));
        return new PageResponse<>(items, page, size, total);
    }

    /**
     * Adds the text condition; returns the SQL expression for relevance, or {@code null} when
     * there is no usable text.
     */
    private static String textCondition(String text, Conditions where) {
        if (text == null || text.isBlank()) {
            return null;
        }
        List<String> terms = new ArrayList<>();
        Matcher words = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (words.find() && terms.size() < MAX_TERMS) {
            terms.add(words.group());
        }
        List<String> alternatives = new ArrayList<>();
        String rank = "0";
        if (!terms.isEmpty()) {
            // Only letters and digits reach the tsquery; ":*" makes every word a prefix match.
            where.params.addValue("tsquery", terms.stream().map(t -> t + ":*").collect(Collectors.joining(" & ")));
            String q = "to_tsquery('simple', :tsquery)";
            alternatives.add("i.search_vector @@ " + q);
            alternatives.add("exists (select 1 from comment c where c.issue_id = i.id and c.search_vector @@ " + q + ")");
            alternatives.add("i.summary ilike :summaryLike escape '\\'");
            where.params.addValue("summaryLike", SqlLike.containsPattern(text));
            rank = "ts_rank(i.search_vector, " + q + ")";
        }
        Matcher key = ISSUE_KEY.matcher(text.trim());
        if (key.matches()) {
            alternatives.add("(p.key = :keyProject and i.number = :keyNumber)");
            where.params.addValue("keyProject", key.group(1).toUpperCase(Locale.ROOT))
                    .addValue("keyNumber", Long.parseLong(key.group(2)));
            rank = "(case when p.key = :keyProject and i.number = :keyNumber then 1000 else 0 end + " + rank + ")";
        }
        if (alternatives.isEmpty()) {
            return null;
        }
        where.add("(" + String.join("\n       or ", alternatives) + ")");
        return rank;
    }

    private static void ids(Conditions where, String column, String name, List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            where.add(column + " in (:" + name + ")");
            where.params.addValue(name, ids);
        }
    }

    private static void users(Conditions where, String column, String name, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        List<String> alternatives = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (String value : values) {
            if (IssueQuery.NONE.equals(value)) {
                alternatives.add(column + " is null");
            } else {
                ids.add(IssueQuery.ME.equals(value) ? where.user.id() : Long.parseLong(value));
            }
        }
        if (!ids.isEmpty()) {
            alternatives.add(column + " in (:" + name + ")");
            where.params.addValue(name, ids);
        }
        where.add("(" + String.join(" or ", alternatives) + ")");
    }

    /** Inclusive date range on a timestamp column, with whole days in UTC. */
    private static void dateRange(Conditions where, String column, String name, LocalDate from, LocalDate to) {
        if (from != null) {
            where.add(column + " >= :" + name + "From");
            where.params.addValue(name + "From", java.sql.Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        if (to != null) {
            where.add(column + " < :" + name + "To");
            where.params.addValue(name + "To",
                    java.sql.Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
    }

    private static String orderBy(IssueQuery query, String rank) {
        Sort sort = query.sort() != null ? query.sort() : rank != null ? Sort.RELEVANCE : Sort.UPDATED;
        boolean desc = query.direction() != null ? query.direction() == Direction.DESC : sort != Sort.KEY;
        String dir = desc ? " desc" : " asc";
        String order = switch (sort) {
            case RELEVANCE -> rank != null ? rank + dir + ", i.updated_at desc" : "i.updated_at" + dir;
            case KEY -> "p.key" + dir + ", i.number" + dir;
            case CREATED -> "i.created_at" + dir;
            case UPDATED -> "i.updated_at" + dir;
            // A lower position is a higher priority: "descending" means most important first.
            case PRIORITY -> "pr.position" + (desc ? " asc" : " desc") + " nulls last";
            case STATUS -> "s.position" + dir;
            case DUE_DATE -> "i.due_date" + dir + " nulls last";
        };
        return order + ", i.id" + dir;
    }

    private static IssueSummaryResponse toSummary(ResultSet rs) throws SQLException {
        String projectKey = rs.getString("p_key");
        long priorityId = rs.getLong("pr_id");
        PriorityRef priority = rs.wasNull() ? null : new PriorityRef(priorityId, rs.getString("pr_name"),
                rs.getString("pr_icon"), rs.getString("pr_color"));
        long resolutionId = rs.getLong("r_id");
        ResolutionRef resolution = rs.wasNull() ? null : new ResolutionRef(resolutionId, rs.getString("r_name"));
        long assigneeId = rs.getLong("a_id");
        UserRef assignee = rs.wasNull() ? null
                : new UserRef(assigneeId, rs.getString("a_username"), rs.getString("a_display_name"));
        return new IssueSummaryResponse(rs.getLong("id"), projectKey + "-" + rs.getLong("number"),
                new ProjectRef(rs.getLong("p_id"), projectKey, rs.getString("p_name")), rs.getString("summary"),
                new IssueTypeRef(rs.getLong("it_id"), rs.getString("it_name"), rs.getString("it_icon"),
                        rs.getInt("it_level")),
                new StatusRef(rs.getLong("s_id"), rs.getString("s_name"),
                        Status.Category.valueOf(rs.getString("s_category"))),
                priority, resolution, assignee,
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    /** The WHERE conditions and parameters of one search. */
    private static final class Conditions {
        final BuggyWuggyPrincipal user;
        final List<String> conditions = new ArrayList<>();
        final MapSqlParameterSource params = new MapSqlParameterSource();

        Conditions(BuggyWuggyPrincipal user) {
            this.user = user;
            params.addValue("userId", user.id());
        }

        void add(String condition) {
            conditions.add(condition);
        }
    }
}
