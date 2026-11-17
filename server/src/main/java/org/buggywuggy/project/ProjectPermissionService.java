/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.buggywuggy.demo.DemoMode;
import org.buggywuggy.security.Authorities;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates a project's permission scheme for a user. Permissions are evaluated on every
 * request, so changes to project roles or group membership take effect immediately.
 *
 * <p>Holder types {@code REPORTER} and {@code ASSIGNEE} depend on a specific issue; they are
 * evaluated in the issue context and never match here.
 */
@Service
@Transactional(readOnly = true)
public class ProjectPermissionService {

    /**
     * SQL condition: grant {@code pg} of project {@code p} applies to user {@code :userId}.
     * Shared by single-project evaluation and project listing, so both always agree.
     */
    static final String GRANT_APPLIES_TO_USER = """
            (pg.holder_type in ('ANYONE_LOGGED_IN', 'ANONYMOUS')
             or (pg.holder_type = 'USER' and pg.holder_ref = :userId)
             or (pg.holder_type = 'PROJECT_LEAD' and p.lead_id = :userId)
             or (pg.holder_type = 'GROUP'
                 and pg.holder_ref in (select m.group_id from group_membership m where m.user_id = :userId))
             or (pg.holder_type = 'PROJECT_ROLE' and exists (
                   select 1 from project_role_actor a
                   where a.project_id = p.id
                     and a.project_role_id = pg.holder_ref
                     and (a.user_id = :userId
                          or a.group_id in (select m.group_id from group_membership m where m.user_id = :userId)))))""";

    /** SQL condition: project {@code p} may be browsed by user {@code :userId}. */
    public static final String PROJECT_BROWSABLE = """
            exists (select 1 from permission_grant pg
                    where pg.permission_scheme_id = p.permission_scheme_id
                      and pg.permission = 'BROWSE_PROJECT'
                      and """ + GRANT_APPLIES_TO_USER + ")";

    private final NamedParameterJdbcTemplate jdbc;
    private final DemoMode demo;

    public ProjectPermissionService(NamedParameterJdbcTemplate jdbc, DemoMode demo) {
        this.jdbc = jdbc;
        this.demo = demo;
    }

    public Set<ProjectPermission> permissionsOf(BuggyWuggyPrincipal user, long projectId) {
        Set<ProjectPermission> result = EnumSet.noneOf(ProjectPermission.class);
        jdbc.queryForList("""
                select distinct pg.permission
                from project p
                join permission_grant pg on pg.permission_scheme_id = p.permission_scheme_id
                where p.id = :projectId and """ + GRANT_APPLIES_TO_USER,
                Map.of("projectId", projectId, "userId", user.id()), String.class)
                .forEach(p -> result.add(ProjectPermission.valueOf(p)));
        // Global administrators can configure every project (but need BROWSE_PROJECT to see issues).
        if (user.hasAuthority(Authorities.ADMINISTER)) {
            result.add(ProjectPermission.ADMINISTER_PROJECT);
        }
        if (demo.isDemoUser(user)) {
            return demo.demoPermissions(projectKey(projectId), result);
        }
        return result;
    }

    private String projectKey(long projectId) {
        return jdbc.queryForObject("select key from project where id = :id", Map.of("id", projectId),
                String.class);
    }

    /**
     * Whether active user {@code userId} holds {@code permission} in the project through the
     * permission scheme. Used for checks about other users, e.g. whether someone is assignable.
     */
    public boolean userHas(long userId, long projectId, ProjectPermission permission) {
        if (demo.enabled() && demo.isDemoUser(jdbc.queryForObject(
                "select username from user_account where id = :id", Map.of("id", userId), String.class))) {
            Set<ProjectPermission> granted = schemeGrants(userId, projectId, ProjectPermission.BROWSE_PROJECT)
                    ? Set.of(ProjectPermission.BROWSE_PROJECT) : Set.of();
            return demo.demoPermissions(projectKey(projectId), granted).contains(permission);
        }
        return schemeGrants(userId, projectId, permission);
    }

    private boolean schemeGrants(long userId, long projectId, ProjectPermission permission) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from project p
                    join permission_grant pg on pg.permission_scheme_id = p.permission_scheme_id
                    join user_account u on u.id = :userId and u.active
                    where p.id = :projectId and pg.permission = :permission and """ + GRANT_APPLIES_TO_USER + ")",
                Map.of("projectId", projectId, "userId", userId, "permission", permission.name()), Boolean.class));
    }
}
