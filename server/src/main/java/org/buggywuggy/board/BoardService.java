/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.board;

import static org.buggywuggy.common.ApiException.badRequest;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.buggywuggy.board.BoardDtos.BoardColumn;
import org.buggywuggy.board.BoardDtos.BoardConfigRequest;
import org.buggywuggy.board.BoardDtos.BoardConfigResponse;
import org.buggywuggy.board.BoardDtos.BoardIssue;
import org.buggywuggy.board.BoardDtos.BoardPermissions;
import org.buggywuggy.board.BoardDtos.BoardResponse;
import org.buggywuggy.board.BoardDtos.ColumnConfig;
import org.buggywuggy.board.BoardDtos.ColumnRequest;
import org.buggywuggy.board.BoardDtos.Swimlanes;
import org.buggywuggy.issue.IssueDtos.IssueRef;
import org.buggywuggy.issue.IssueDtos.IssueTypeRef;
import org.buggywuggy.issue.IssueDtos.PriorityRef;
import org.buggywuggy.issue.IssueDtos.ProjectRef;
import org.buggywuggy.issue.IssueDtos.StatusRef;
import org.buggywuggy.issue.IssueType;
import org.buggywuggy.issue.Status;
import org.buggywuggy.project.Project;
import org.buggywuggy.project.ProjectAccess;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.project.ProjectService;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.taskwarrior.Urgency;
import org.buggywuggy.taskwarrior.UrgencyService;
import org.buggywuggy.user.UserDtos.UserRef;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kanban boards: the default board (configured by the administrator) and the boards of
 * projects with columns of their own. A board shows the unresolved issues of its project and
 * those resolved in the last days, in board order ({@code issue.board_rank}).
 */
@Service
@Transactional
public class BoardService {

    /** At most this many issues are shown on a board. */
    static final int MAX_ISSUES = 1000;

    private final NamedParameterJdbcTemplate jdbc;
    private final ProjectService projects;
    private final UrgencyService urgency;
    private final Clock clock;

    public BoardService(NamedParameterJdbcTemplate jdbc, ProjectService projects, UrgencyService urgency,
            Clock clock) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.urgency = urgency;
        this.clock = clock;
    }

    // --- configuration ---------------------------------------------------------------

    private record Board(long id, boolean own, Swimlanes swimlanes, int resolvedDays) {
    }

    private Board defaultBoard() {
        return jdbc.queryForObject("select id, swimlanes, resolved_days from board where project_id is null",
                Map.of(), (rs, n) -> new Board(rs.getLong("id"), false, Swimlanes.valueOf(rs.getString("swimlanes")),
                        rs.getInt("resolved_days")));
    }

    /** The board of a project: its own, or else the default. */
    private Board boardOf(long projectId) {
        return jdbc.query("select id, swimlanes, resolved_days from board where project_id = :project",
                        Map.of("project", projectId), (rs, n) -> new Board(rs.getLong("id"), true,
                                Swimlanes.valueOf(rs.getString("swimlanes")), rs.getInt("resolved_days")))
                .stream().findFirst().orElseGet(this::defaultBoard);
    }

    private BoardConfigResponse config(Board board) {
        Map<Long, ColumnConfig> columns = new LinkedHashMap<>();
        jdbc.query("""
                select c.id, c.name, cs.status_id
                from board_column c
                left join board_column_status cs on cs.board_column_id = c.id
                left join status s on s.id = cs.status_id
                where c.board_id = :board
                order by c.position, c.id, s.position, s.id""", Map.of("board", board.id()), rs -> {
            ColumnConfig column = columns.computeIfAbsent(rs.getLong("id"),
                    id -> new ColumnConfig(id, string(rs, "name"), new ArrayList<>()));
            long statusId = rs.getLong("status_id");
            if (!rs.wasNull()) {
                column.statusIds().add(statusId);
            }
        });
        return new BoardConfigResponse(board.own(), board.swimlanes(), board.resolvedDays(),
                List.copyOf(columns.values()));
    }

    @Transactional(readOnly = true)
    public BoardConfigResponse defaultConfig() {
        return config(defaultBoard());
    }

    public BoardConfigResponse saveDefault(BoardConfigRequest request) {
        Board board = defaultBoard();
        save(board.id(), request);
        return defaultConfig();
    }

    /** The columns of a project's board, for its administrators. */
    @Transactional(readOnly = true)
    public BoardConfigResponse projectConfig(BuggyWuggyPrincipal user, String projectIdOrKey) {
        ProjectAccess access = projects.access(user, projectIdOrKey).require(ProjectPermission.ADMINISTER_PROJECT);
        return config(boardOf(access.projectId()));
    }

    /** Gives the project columns of its own (or changes them). */
    public BoardConfigResponse saveProject(BuggyWuggyPrincipal user, String projectIdOrKey,
            BoardConfigRequest request) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        Long id = jdbc.query("select id from board where project_id = :project for update",
                Map.of("project", access.projectId()), (rs, n) -> rs.getLong(1)).stream().findFirst().orElse(null);
        if (id == null) {
            id = jdbc.queryForObject("insert into board (project_id) values (:project) returning id",
                    Map.of("project", access.projectId()), Long.class);
        }
        save(id, request);
        return config(boardOf(access.projectId()));
    }

    /** Lets the project use the default board again. */
    public BoardConfigResponse resetProject(BuggyWuggyPrincipal user, String projectIdOrKey) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        jdbc.update("delete from board where project_id = :project", Map.of("project", access.projectId()));
        return config(defaultBoard());
    }

    private void save(long boardId, BoardConfigRequest request) {
        Set<String> names = new HashSet<>();
        Set<Long> statusIds = new HashSet<>();
        for (ColumnRequest column : request.columns()) {
            if (!names.add(column.name().strip().toLowerCase(Locale.ROOT))) {
                throw badRequest("board.column-duplicate", column.name().strip());
            }
            for (Long statusId : column.statusIds()) {
                if (!statusIds.add(statusId)) {
                    throw badRequest("board.status-twice", statusId);
                }
            }
        }
        if (!statusIds.isEmpty()) {
            Set<Long> known = new HashSet<>(jdbc.queryForList("select id from status where id in (:ids)",
                    Map.of("ids", statusIds), Long.class));
            statusIds.stream().filter(id -> !known.contains(id)).findFirst()
                    .ifPresent(id -> { throw badRequest("board.status-invalid", id); });
        }
        jdbc.update("update board set swimlanes = :swimlanes, resolved_days = :days where id = :board",
                new MapSqlParameterSource().addValue("swimlanes", request.swimlanes().name())
                        .addValue("days", request.resolvedDays()).addValue("board", boardId));
        jdbc.update("delete from board_column where board_id = :board", Map.of("board", boardId));
        int position = 0;
        for (ColumnRequest column : request.columns()) {
            long columnId = jdbc.queryForObject("""
                    insert into board_column (board_id, name, position) values (:board, :name, :position)
                    returning id""", new MapSqlParameterSource().addValue("board", boardId)
                    .addValue("name", column.name().strip()).addValue("position", position++), Long.class);
            for (Long statusId : column.statusIds()) {
                jdbc.update("""
                        insert into board_column_status (board_column_id, board_id, status_id)
                        values (:column, :board, :status)""",
                        Map.of("column", columnId, "board", boardId, "status", statusId));
            }
        }
    }

    // --- the board -------------------------------------------------------------------

    @Transactional(readOnly = true)
    public BoardResponse board(BuggyWuggyPrincipal user, String projectIdOrKey) {
        ProjectAccess access = projects.access(user, projectIdOrKey).require(ProjectPermission.BROWSE_PROJECT);
        Project project = access.project();
        Board board = boardOf(project.getId());

        Map<Long, StatusRef> statuses = new LinkedHashMap<>();
        jdbc.query("select id, name, category from status", rs -> {
            statuses.put(rs.getLong("id"), new StatusRef(rs.getLong("id"), rs.getString("name"),
                    Status.Category.valueOf(rs.getString("category"))));
        });
        List<BoardColumn> columns = config(board).columns().stream()
                .map(c -> new BoardColumn(c.id(), c.name(), c.statusIds().stream().map(statuses::get).toList()))
                .toList();

        String key = project.getProjectKey();
        List<BoardIssue> issues = new ArrayList<>(jdbc.query("""
                select i.id, i.number, i.summary, i.version, cs.board_column_id,
                       t.id as type_id, t.name as type_name, t.icon as type_icon, t.hierarchy_level,
                       i.status_id, p.id as priority_id, p.name as priority_name, p.icon as priority_icon,
                       p.color as priority_color, u.id as user_id, u.username, u.display_name,
                       par.id as parent_id, par.number as parent_number, par.summary as parent_summary,
                       ep.id as epic_id, ep.number as epic_number, ep.summary as epic_summary
                from issue i
                join board_column_status cs on cs.board_id = :board and cs.status_id = i.status_id
                join status s on s.id = i.status_id
                join issue_type t on t.id = i.issue_type_id
                left join priority p on p.id = i.priority_id
                left join user_account u on u.id = i.assignee_id
                left join issue par on par.id = i.parent_id
                left join issue_type pt on pt.id = par.issue_type_id
                left join issue ep on ep.id = case
                        when t.hierarchy_level = :epic then i.id
                        when pt.hierarchy_level = :epic then par.id
                        else par.parent_id end
                where i.project_id = :project
                  and (i.resolution_id is null and s.category <> 'DONE'
                       or coalesce(i.resolved_at, i.updated_at) >= :since)
                order by i.board_rank nulls last, i.id
                limit :limit""", new MapSqlParameterSource()
                .addValue("board", board.id()).addValue("project", project.getId())
                .addValue("epic", IssueType.EPIC).addValue("limit", MAX_ISSUES + 1)
                .addValue("since", Timestamp.from(clock.instant().minus(Duration.ofDays(board.resolvedDays())))),
                (rs, n) -> new BoardIssue(rs.getLong("id"), key + "-" + rs.getLong("number"),
                        rs.getString("summary"),
                        new IssueTypeRef(rs.getLong("type_id"), rs.getString("type_name"),
                                rs.getString("type_icon"), rs.getInt("hierarchy_level")),
                        statuses.get(rs.getLong("status_id")),
                        rs.getObject("priority_id") == null ? null : new PriorityRef(rs.getLong("priority_id"),
                                rs.getString("priority_name"), rs.getString("priority_icon"),
                                rs.getString("priority_color")),
                        rs.getObject("user_id") == null ? null : new UserRef(rs.getLong("user_id"),
                                rs.getString("username"), rs.getString("display_name")),
                        ref(rs, key, "parent"), ref(rs, key, "epic"), null, rs.getLong("version"),
                        rs.getLong("board_column_id"))));
        boolean truncated = issues.size() > MAX_ISSUES;
        if (truncated) {
            issues.subList(MAX_ISSUES, issues.size()).clear();
        }
        Map<Long, Urgency.Result> urgencies = urgency.of(issues.stream().map(BoardIssue::id).toList());
        List<BoardIssue> withUrgency = issues.stream().map(i -> new BoardIssue(i.id(), i.key(), i.summary(),
                i.issueType(), i.status(), i.priority(), i.assignee(), i.parent(), i.epic(),
                urgencies.containsKey(i.id()) ? urgencies.get(i.id()).urgency() : null, i.version(),
                i.columnId())).toList();

        List<StatusRef> unmapped = jdbc.queryForList("""
                select distinct ws.status_id
                from workflow_scheme_entry e
                join workflow_status ws on ws.workflow_id = e.workflow_id
                where e.workflow_scheme_id = :scheme
                  and ws.status_id not in (select status_id from board_column_status where board_id = :board)""",
                Map.of("scheme", project.getWorkflowSchemeId(), "board", board.id()), Long.class).stream()
                .map(statuses::get).sorted((a, b) -> a.name().compareToIgnoreCase(b.name())).toList();

        boolean writable = !project.isArchived();
        BoardPermissions permissions = new BoardPermissions(
                writable && access.has(ProjectPermission.TRANSITION_ISSUE),
                writable && access.has(ProjectPermission.ASSIGN_ISSUE),
                writable && access.has(ProjectPermission.EDIT_ISSUE),
                writable && access.has(ProjectPermission.ADMINISTER_PROJECT));
        return new BoardResponse(new ProjectRef(project.getId(), key, project.getName()), board.own(),
                board.swimlanes(), board.resolvedDays(), columns, withUrgency, truncated, unmapped, permissions);
    }

    private static IssueRef ref(ResultSet rs, String projectKey, String prefix) throws SQLException {
        long id = rs.getLong(prefix + "_id");
        return rs.wasNull() ? null
                : new IssueRef(id, projectKey + "-" + rs.getLong(prefix + "_number"), rs.getString(prefix + "_summary"));
    }

    private static String string(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
