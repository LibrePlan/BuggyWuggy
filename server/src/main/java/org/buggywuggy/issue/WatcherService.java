/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import static org.buggywuggy.common.ApiException.badRequest;

import java.util.List;
import java.util.Map;
import org.buggywuggy.issue.CollaborationDtos.WatchersResponse;
import org.buggywuggy.issue.IssueService.IssueAccess;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.project.ProjectPermissionService;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserDtos.UserRef;
import org.buggywuggy.user.UserService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Watchers of an issue. Everyone who can browse an issue may watch it; adding or removing
 * other users and seeing who watches requires MANAGE_WATCHERS.
 */
@Service
@Transactional
public class WatcherService {

    private final IssueService issues;
    private final IssueFieldStore fields;
    private final ProjectPermissionService permissions;
    private final UserService users;
    private final NamedParameterJdbcTemplate jdbc;
    private final java.time.Clock clock;

    public WatcherService(IssueService issues, IssueFieldStore fields, ProjectPermissionService permissions,
            UserService users, NamedParameterJdbcTemplate jdbc, java.time.Clock clock) {
        this.issues = issues;
        this.fields = fields;
        this.permissions = permissions;
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public WatchersResponse get(BuggyWuggyPrincipal user, String issueIdOrKey) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        long issueId = access.issue().getId();
        List<UserRef> watchers = jdbc.query("""
                select u.id, u.username, u.display_name from watcher w join user_account u on u.id = w.user_id
                where w.issue_id = :issue order by lower(u.display_name), u.id""",
                Map.of("issue", issueId), (rs, n) -> new UserRef(rs.getLong(1), rs.getString(2), rs.getString(3)));
        boolean watching = watchers.stream().anyMatch(w -> w.id() == user.id());
        boolean mayList = access.project().has(ProjectPermission.MANAGE_WATCHERS);
        return new WatchersResponse(watching, watchers.size(), mayList ? watchers : null);
    }

    public WatchersResponse add(BuggyWuggyPrincipal user, String issueIdOrKey, long userId) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        long issueId = access.issue().getId();
        if (userId != user.id()) {
            access.project().require(ProjectPermission.MANAGE_WATCHERS);
            users.requireActiveUser(userId, "watcher.user-invalid");
            if (!permissions.userHas(userId, access.project().projectId(), ProjectPermission.BROWSE_PROJECT)) {
                throw badRequest("watcher.cannot-browse");
            }
        }
        fields.addWatcher(issueId, userId, clock.instant());
        return get(user, issueIdOrKey);
    }

    public WatchersResponse remove(BuggyWuggyPrincipal user, String issueIdOrKey, long userId) {
        IssueAccess access = issues.access(user, issueIdOrKey);
        if (userId != user.id()) {
            access.project().require(ProjectPermission.MANAGE_WATCHERS);
        }
        fields.removeWatcher(access.issue().getId(), userId);
        return get(user, issueIdOrKey);
    }
}
