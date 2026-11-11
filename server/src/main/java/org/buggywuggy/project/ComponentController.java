/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import jakarta.validation.Valid;
import java.util.List;
import org.buggywuggy.project.ProjectDtos.ComponentResponse;
import org.buggywuggy.project.ProjectDtos.CreateComponentRequest;
import org.buggywuggy.project.ProjectDtos.UpdateComponentRequest;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{idOrKey}/components")
public class ComponentController {

    private final ComponentService componentService;

    public ComponentController(ComponentService componentService) {
        this.componentService = componentService;
    }

    @GetMapping
    public List<ComponentResponse> list(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return componentService.list(user, idOrKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentResponse create(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @Valid @RequestBody CreateComponentRequest request) {
        return componentService.create(user, idOrKey, request);
    }

    @PatchMapping("/{componentId}")
    public ComponentResponse update(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long componentId, @Valid @RequestBody UpdateComponentRequest request) {
        return componentService.update(user, idOrKey, componentId, request);
    }

    @DeleteMapping("/{componentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long componentId) {
        componentService.delete(user, idOrKey, componentId);
    }
}
