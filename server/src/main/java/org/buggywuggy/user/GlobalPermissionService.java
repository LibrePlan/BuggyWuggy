/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import static org.buggywuggy.common.ApiException.forbidden;
import static org.buggywuggy.common.ApiException.notFound;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.buggywuggy.security.Authorities;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.security.SessionInvalidator;
import org.buggywuggy.user.UserDtos.GroupRef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which groups hold which global permission. Global permissions are part of a session's
 * authorities, so a change ends the sessions of the group's members; they get the new
 * permissions when they log in again.
 */
@Service
@Transactional
public class GlobalPermissionService {

    /** In the order they are shown. */
    static final List<String> PERMISSIONS =
            List.of(Authorities.ADMINISTER, Authorities.CREATE_PROJECT, Authorities.BROWSE_USERS);

    public record GlobalPermissionResponse(String permission, List<GroupRef> groups) {
    }

    private final JdbcTemplate jdbc;
    private final SessionInvalidator sessions;

    public GlobalPermissionService(JdbcTemplate jdbc, SessionInvalidator sessions) {
        this.jdbc = jdbc;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    public List<GlobalPermissionResponse> list() {
        Map<String, List<GroupRef>> byPermission = new LinkedHashMap<>();
        PERMISSIONS.forEach(p -> byPermission.put(p, new ArrayList<>()));
        jdbc.query("""
                select gp.permission, g.id, g.name
                from global_permission_grant gp join user_group g on g.id = gp.group_id
                order by lower(g.name), g.id""", rs -> {
            byPermission.get(rs.getString(1)).add(new GroupRef(rs.getLong(2), rs.getString(3)));
        });
        return byPermission.entrySet().stream()
                .map(e -> new GlobalPermissionResponse(e.getKey(), List.copyOf(e.getValue())))
                .toList();
    }

    public void grant(String permission, long groupId) {
        requireKnown(permission);
        requireGroup(groupId);
        int added = jdbc.update("""
                insert into global_permission_grant (permission, group_id) values (?, ?)
                on conflict do nothing""", permission, groupId);
        if (added > 0) {
            endSessionsOfMembers(groupId);
        }
    }

    public void revoke(BuggyWuggyPrincipal actor, String permission, long groupId) {
        requireKnown(permission);
        requireGroup(groupId);
        if (permission.equals(Authorities.ADMINISTER) && !actor.isSystemAdmin()
                && !administersWithout(actor.id(), groupId)) {
            // Would lock the caller out of administration; a system administrator can do it.
            throw forbidden("global-permission.cannot-revoke-own");
        }
        int removed = jdbc.update("delete from global_permission_grant where permission = ? and group_id = ?",
                permission, groupId);
        if (removed > 0) {
            endSessionsOfMembers(groupId);
        }
    }

    /** Whether the user holds ADMINISTER through a group other than {@code groupId}. */
    private boolean administersWithout(long userId, long groupId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from global_permission_grant gp
                    join group_membership m on m.group_id = gp.group_id
                    where gp.permission = 'ADMINISTER' and m.user_id = ? and gp.group_id <> ?)""",
                Boolean.class, userId, groupId));
    }

    private void endSessionsOfMembers(long groupId) {
        sessions.invalidateAll(jdbc.queryForList("""
                select u.username from user_account u join group_membership m on m.user_id = u.id
                where m.group_id = ?""", String.class, groupId));
    }

    private static void requireKnown(String permission) {
        if (!PERMISSIONS.contains(permission)) {
            throw notFound("global-permission.not-found", permission);
        }
    }

    private void requireGroup(long groupId) {
        Boolean exists = jdbc.queryForObject("select exists (select 1 from user_group where id = ?)",
                Boolean.class, groupId);
        if (!Boolean.TRUE.equals(exists)) {
            throw notFound("group.not-found", groupId);
        }
    }
}
