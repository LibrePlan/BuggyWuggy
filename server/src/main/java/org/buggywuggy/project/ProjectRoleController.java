/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import java.util.List;
import org.buggywuggy.project.ProjectDtos.ProjectRoleMembersResponse;
import org.buggywuggy.project.ProjectDtos.ProjectRoleResponse;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProjectRoleController {

    private final ProjectRoleService roleService;

    public ProjectRoleController(ProjectRoleService roleService) {
        this.roleService = roleService;
    }

    /** All project roles (roles are global; members are set per project). */
    @GetMapping("/api/v1/project-roles")
    public List<ProjectRoleResponse> allRoles() {
        return roleService.allRoles();
    }

    @GetMapping("/api/v1/projects/{idOrKey}/roles")
    public List<ProjectRoleMembersResponse> members(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return roleService.members(user, idOrKey);
    }

    @PutMapping("/api/v1/projects/{idOrKey}/roles/{roleId}/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addUser(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long roleId, @PathVariable long userId) {
        roleService.addUser(user, idOrKey, roleId, userId);
    }

    @DeleteMapping("/api/v1/projects/{idOrKey}/roles/{roleId}/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeUser(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long roleId, @PathVariable long userId) {
        roleService.removeUser(user, idOrKey, roleId, userId);
    }

    @PutMapping("/api/v1/projects/{idOrKey}/roles/{roleId}/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addGroup(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long roleId, @PathVariable long groupId) {
        roleService.addGroup(user, idOrKey, roleId, groupId);
    }

    @DeleteMapping("/api/v1/projects/{idOrKey}/roles/{roleId}/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeGroup(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long roleId, @PathVariable long groupId) {
        roleService.removeGroup(user, idOrKey, roleId, groupId);
    }
}
