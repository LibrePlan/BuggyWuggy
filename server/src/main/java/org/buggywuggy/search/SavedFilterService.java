/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.search;

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
import java.util.Objects;
import org.buggywuggy.issue.IssueDtos.ProjectRef;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.project.ProjectPermissionService;
import org.buggywuggy.search.SavedFilterDtos.CreateFilterRequest;
import org.buggywuggy.search.SavedFilterDtos.FilterResponse;
import org.buggywuggy.search.SavedFilterDtos.SharedWith;
import org.buggywuggy.search.SavedFilterDtos.UpdateFilterRequest;
import org.buggywuggy.security.Authorities;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserDtos.UserRef;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved filters. The query is stored as JSON, so it can grow new conditions without schema
 * changes. A filter is visible to its owner and, depending on {@code shared_with}, to all
 * logged-in users or to everyone who can browse its project.
 */
@Service
@Transactional
public class SavedFilterService {

    private static final String SELECT = """
            select f.id, f.name, f.description, f.query::text as query, f.shared_with, f.created_at, f.updated_at,
                   f.owner_id, o.username as o_username, o.display_name as o_display_name,
                   p.id as p_id, p.key as p_key, p.name as p_name
            from saved_filter f
            join user_account o on o.id = f.owner_id
            left join project p on p.id = f.project_id
            """;

    /** Filters the user may use: own ones and shared ones. */
    private static final String VISIBLE = """
            (f.owner_id = :userId
             or f.shared_with = 'LOGGED_IN'
             or (f.shared_with = 'PROJECT' and exists (
                   select 1 from project p where p.id = f.project_id and """
            + " " + ProjectPermissionService.PROJECT_BROWSABLE + ")))";

    private final NamedParameterJdbcTemplate jdbc;
    private final ProjectPermissionService permissions;
    private final JsonMapper json;
    private final Clock clock;

    public SavedFilterService(NamedParameterJdbcTemplate jdbc, ProjectPermissionService permissions, JsonMapper json,
            Clock clock) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.json = json;
        this.clock = clock;
    }

    /** Own filters first, then shared ones, each by name. */
    @Transactional(readOnly = true)
    public List<FilterResponse> list(BuggyWuggyPrincipal user) {
        return jdbc.query(SELECT + " where " + VISIBLE
                        + " order by (f.owner_id = :userId) desc, lower(f.name), f.id",
                Map.of("userId", user.id()), (rs, n) -> toResponse(rs, user));
    }

    @Transactional(readOnly = true)
    public FilterResponse get(BuggyWuggyPrincipal user, long id) {
        return jdbc.query(SELECT + " where f.id = :id and " + VISIBLE, Map.of("id", id, "userId", user.id()),
                        (rs, n) -> toResponse(rs, user))
                .stream().findFirst().orElseThrow(() -> notFound("filter.not-found", id));
    }

    public FilterResponse create(BuggyWuggyPrincipal user, CreateFilterRequest request) {
        SharedWith sharedWith = Objects.requireNonNullElse(request.sharedWith(), SharedWith.PRIVATE);
        Long projectId = validSharing(user, sharedWith, request.projectId());
        String name = request.name().trim();
        requireNameAvailable(user.id(), name, null);
        Instant now = clock.instant();
        Long id = jdbc.queryForObject("""
                insert into saved_filter (owner_id, name, description, query, shared_with, project_id,
                                          created_at, updated_at)
                values (:owner, :name, :description, cast(:query as jsonb), :sharedWith, :project, :now, :now)
                returning id""",
                new MapSqlParameterSource().addValue("owner", user.id()).addValue("name", name)
                        .addValue("description", blankToNull(request.description()))
                        .addValue("query", json.writeValueAsString(request.query()))
                        .addValue("sharedWith", sharedWith.name()).addValue("project", projectId)
                        .addValue("now", Timestamp.from(now)),
                Long.class);
        return get(user, id);
    }

    public FilterResponse update(BuggyWuggyPrincipal user, long id, UpdateFilterRequest request) {
        FilterResponse current = get(user, id);
        if (!current.mine()) {
            throw forbidden("filter.owner-only");
        }
        String name = request.name() != null ? request.name().trim() : current.name();
        if (!name.equalsIgnoreCase(current.name())) {
            requireNameAvailable(user.id(), name, id);
        }
        SharedWith sharedWith = Objects.requireNonNullElse(request.sharedWith(), current.sharedWith());
        Long requestedProject = request.projectId() != null ? request.projectId()
                : current.project() == null ? null : current.project().id();
        Long projectId = validSharing(user, sharedWith, sharedWith == SharedWith.PROJECT ? requestedProject : null);
        jdbc.update("""
                update saved_filter set name = :name, description = :description, query = cast(:query as jsonb),
                       shared_with = :sharedWith, project_id = :project, updated_at = :now
                where id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("name", name)
                        .addValue("description", request.description() != null
                                ? blankToNull(request.description()) : current.description())
                        .addValue("query", json.writeValueAsString(
                                request.query() != null ? request.query() : current.query()))
                        .addValue("sharedWith", sharedWith.name()).addValue("project", projectId)
                        .addValue("now", Timestamp.from(clock.instant())));
        return get(user, id);
    }

    /** The owner deletes a filter; global administrators can delete any filter they can see. */
    public void delete(BuggyWuggyPrincipal user, long id) {
        FilterResponse current = get(user, id);
        if (!current.mine() && !user.hasAuthority(Authorities.ADMINISTER)) {
            throw forbidden("filter.owner-only");
        }
        jdbc.update("delete from saved_filter where id = :id", Map.of("id", id));
    }

    private Long validSharing(BuggyWuggyPrincipal user, SharedWith sharedWith, Long projectId) {
        if (sharedWith != SharedWith.PROJECT) {
            if (projectId != null) {
                throw badRequest("filter.project-not-allowed");
            }
            return null;
        }
        if (projectId == null) {
            throw badRequest("filter.project-required");
        }
        if (!permissions.permissionsOf(user, projectId).contains(ProjectPermission.BROWSE_PROJECT)) {
            throw badRequest("filter.project-invalid", projectId);
        }
        return projectId;
    }

    private void requireNameAvailable(long ownerId, String name, Long exceptId) {
        boolean taken = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from saved_filter
                               where owner_id = :owner and lower(name) = lower(:name)
                                 and (cast(:except as bigint) is null or id <> :except))""",
                new MapSqlParameterSource().addValue("owner", ownerId).addValue("name", name)
                        .addValue("except", exceptId), Boolean.class));
        if (taken) {
            throw conflict("filter.name-taken", name);
        }
    }

    private FilterResponse toResponse(ResultSet rs, BuggyWuggyPrincipal user) throws SQLException {
        long ownerId = rs.getLong("owner_id");
        long projectId = rs.getLong("p_id");
        ProjectRef project = rs.wasNull() ? null
                : new ProjectRef(projectId, rs.getString("p_key"), rs.getString("p_name"));
        return new FilterResponse(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                json.readValue(rs.getString("query"), IssueQuery.class),
                SharedWith.valueOf(rs.getString("shared_with")), project,
                new UserRef(ownerId, rs.getString("o_username"), rs.getString("o_display_name")),
                ownerId == user.id(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
