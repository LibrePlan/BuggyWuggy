/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import static org.buggywuggy.common.ApiException.notFound;

import java.util.Comparator;
import java.util.List;
import org.buggywuggy.project.ProjectDtos.ProjectRoleMembersResponse;
import org.buggywuggy.project.ProjectDtos.ProjectRoleResponse;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserDtos.GroupRef;
import org.buggywuggy.user.UserDtos.UserRef;
import org.buggywuggy.user.UserGroup;
import org.buggywuggy.user.UserGroupRepository;
import org.buggywuggy.user.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Membership of project roles. Changes take effect immediately (permissions are evaluated per request). */
@Service
@Transactional
public class ProjectRoleService {

    private final ProjectService projects;
    private final ProjectRoleRepository roles;
    private final ProjectRoleActorRepository actors;
    private final UserService users;
    private final UserGroupRepository groups;

    public ProjectRoleService(ProjectService projects, ProjectRoleRepository roles, ProjectRoleActorRepository actors,
            UserService users, UserGroupRepository groups) {
        this.projects = projects;
        this.roles = roles;
        this.actors = actors;
        this.users = users;
        this.groups = groups;
    }

    @Transactional(readOnly = true)
    public List<ProjectRoleResponse> allRoles() {
        return roles.findAllOrdered().stream().map(ProjectRoleResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectRoleMembersResponse> members(BuggyWuggyPrincipal user, String projectIdOrKey) {
        ProjectAccess access = projects.access(user, projectIdOrKey);
        List<ProjectRoleActor> all = actors.findByProject(access.projectId());
        return roles.findAllOrdered().stream().map(role -> {
            List<ProjectRoleActor> ofRole = all.stream().filter(a -> a.getProjectRoleId() == role.getId()).toList();
            List<UserRef> roleUsers = ofRole.stream().filter(a -> a.getUser() != null)
                    .map(a -> UserRef.of(a.getUser()))
                    .sorted(Comparator.comparing(UserRef::username, String.CASE_INSENSITIVE_ORDER)).toList();
            List<GroupRef> roleGroups = ofRole.stream().filter(a -> a.getGroup() != null)
                    .map(a -> new GroupRef(a.getGroup().getId(), a.getGroup().getName()))
                    .sorted(Comparator.comparing(GroupRef::name, String.CASE_INSENSITIVE_ORDER)).toList();
            return new ProjectRoleMembersResponse(role.getId(), role.getName(), role.getDescription(),
                    roleUsers, roleGroups);
        }).toList();
    }

    public void addUser(BuggyWuggyPrincipal user, String projectIdOrKey, long roleId, long userId) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        ProjectRole role = findRole(roleId);
        if (actors.findUserActor(access.projectId(), role.getId(), userId).isEmpty()) {
            actors.save(ProjectRoleActor.ofUser(access.projectId(), role.getId(),
                    users.requireActiveUser(userId, "project-role.user-invalid")));
        }
    }

    public void removeUser(BuggyWuggyPrincipal user, String projectIdOrKey, long roleId, long userId) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        actors.findUserActor(access.projectId(), findRole(roleId).getId(), userId).ifPresent(actors::delete);
    }

    public void addGroup(BuggyWuggyPrincipal user, String projectIdOrKey, long roleId, long groupId) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        ProjectRole role = findRole(roleId);
        UserGroup group = groups.findById(groupId).orElseThrow(() -> notFound("group.not-found", groupId));
        if (actors.findGroupActor(access.projectId(), role.getId(), groupId).isEmpty()) {
            actors.save(ProjectRoleActor.ofGroup(access.projectId(), role.getId(), group));
        }
    }

    public void removeGroup(BuggyWuggyPrincipal user, String projectIdOrKey, long roleId, long groupId) {
        ProjectAccess access = projects.access(user, projectIdOrKey).requireConfigurable();
        actors.findGroupActor(access.projectId(), findRole(roleId).getId(), groupId).ifPresent(actors::delete);
    }

    private ProjectRole findRole(long roleId) {
        return roles.findById(roleId).orElseThrow(() -> notFound("project-role.not-found", roleId));
    }
}
