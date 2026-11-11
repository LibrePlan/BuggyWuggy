/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.forbidden;
import static org.buggywuggy.common.ApiException.notFound;

import java.util.List;
import org.buggywuggy.common.PageResponse;
import org.buggywuggy.security.SessionInvalidator;
import org.buggywuggy.user.GroupResponse.CreateGroupRequest;
import org.buggywuggy.user.GroupResponse.UpdateGroupRequest;
import org.buggywuggy.user.UserDtos.UserResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GroupService {

    private final UserGroupRepository groups;
    private final UserAccountRepository users;
    private final SessionInvalidator sessions;
    private final JdbcTemplate jdbc;

    public GroupService(UserGroupRepository groups, UserAccountRepository users, SessionInvalidator sessions,
            JdbcTemplate jdbc) {
        this.groups = groups;
        this.users = users;
        this.sessions = sessions;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<GroupResponse> list(int page, int size) {
        return PageResponse.of(groups.findAllWithMemberCount(PageRequest.of(page, size)), g -> g);
    }

    @Transactional(readOnly = true)
    public GroupResponse get(long id) {
        return toResponse(find(id));
    }

    public GroupResponse create(CreateGroupRequest request) {
        String name = request.name().trim();
        if (groups.existsByNameExcluding(name, -1)) {
            throw conflict("group.name-taken", name);
        }
        return toResponse(groups.save(new UserGroup(name, request.description())));
    }

    public GroupResponse update(long id, UpdateGroupRequest request) {
        UserGroup group = find(id);
        if (request.name() != null && !request.name().trim().equals(group.getName())) {
            String name = request.name().trim();
            if (group.isBuiltIn()) {
                throw forbidden("group.protected", group.getName());
            }
            if (groups.existsByNameExcluding(name, id)) {
                throw conflict("group.name-taken", name);
            }
            group.setName(name);
        }
        if (request.description() != null) {
            group.setDescription(request.description().isBlank() ? null : request.description());
        }
        return toResponse(group);
    }

    public void delete(long id) {
        UserGroup group = find(id);
        if (group.isBuiltIn()) {
            throw forbidden("group.protected", group.getName());
        }
        List<String> affected = grantsGlobalPermissions(group)
                ? group.getMembers().stream().map(UserAccount::getUsername).toList()
                : List.of();
        group.getMembers().forEach(u -> u.getGroups().remove(group));
        groups.delete(group);
        sessions.invalidateAll(affected);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> members(long groupId, int page, int size) {
        find(groupId);
        return PageResponse.of(users.findByGroup(groupId, PageRequest.of(page, size)), UserResponse::of);
    }

    public void addMember(long groupId, long userId) {
        UserGroup group = find(groupId);
        UserAccount user = findUser(userId);
        if (user.getGroups().add(group) && grantsGlobalPermissions(group)) {
            sessions.invalidateAll(user.getUsername());
        }
    }

    public void removeMember(long groupId, long userId) {
        UserGroup group = find(groupId);
        UserAccount user = findUser(userId);
        if (user.getGroups().remove(group) && grantsGlobalPermissions(group)) {
            sessions.invalidateAll(user.getUsername());
        }
    }

    /** Membership of such a group changes a user's session authorities. */
    private boolean grantsGlobalPermissions(UserGroup group) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from global_permission_grant where group_id = ?)",
                Boolean.class, group.getId()));
    }

    private UserGroup find(long id) {
        return groups.findById(id).orElseThrow(() -> notFound("group.not-found", id));
    }

    private UserAccount findUser(long id) {
        return users.findById(id).orElseThrow(() -> notFound("user.not-found", id));
    }

    private static GroupResponse toResponse(UserGroup g) {
        return new GroupResponse(g.getId(), g.getName(), g.getDescription(), g.getMembers().size());
    }
}
