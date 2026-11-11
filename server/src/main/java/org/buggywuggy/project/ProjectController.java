/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.buggywuggy.user.UserDtos.UserRef;
import org.buggywuggy.common.PageResponse;
import org.buggywuggy.project.ProjectDtos.CreateProjectRequest;
import org.buggywuggy.project.ProjectDtos.MyProjectPermissionsResponse;
import org.buggywuggy.project.ProjectDtos.ProjectResponse;
import org.buggywuggy.project.ProjectDtos.UpdateProjectRequest;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Projects. {@code idOrKey} accepts the numeric id, the current key or a former key. */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public PageResponse<ProjectResponse> list(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return projectService.list(user, query, includeArchived, page, size);
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse created = projectService.create(user, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.key())).body(created);
    }

    @GetMapping("/{idOrKey}")
    public ProjectResponse get(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey) {
        return projectService.get(user, idOrKey);
    }

    @PatchMapping("/{idOrKey}")
    public ProjectResponse update(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @Valid @RequestBody UpdateProjectRequest request) {
        return projectService.update(user, idOrKey, request);
    }

    /** Deletes the project and all its issues. Requires the global ADMINISTER permission. */
    @DeleteMapping("/{idOrKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey) {
        projectService.delete(user, idOrKey);
    }

    /** Users who can be assigned issues in this project (for assignee pickers). */
    @GetMapping("/{idOrKey}/assignable-users")
    public List<UserRef> assignableUsers(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey, @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return projectService.assignableUsers(user, idOrKey, query, limit);
    }

    /** The current user's permissions in this project, e.g. to show or hide UI actions. */
    @GetMapping("/{idOrKey}/my-permissions")
    public MyProjectPermissionsResponse myPermissions(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return new MyProjectPermissionsResponse(projectService.myPermissions(user, idOrKey));
    }
}
