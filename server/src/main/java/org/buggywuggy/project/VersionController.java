/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import jakarta.validation.Valid;
import java.util.List;
import org.buggywuggy.project.ProjectDtos.CreateVersionRequest;
import org.buggywuggy.project.ProjectDtos.UpdateVersionRequest;
import org.buggywuggy.project.ProjectDtos.VersionOrderRequest;
import org.buggywuggy.project.ProjectDtos.VersionResponse;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{idOrKey}/versions")
public class VersionController {

    private final VersionService versionService;

    public VersionController(VersionService versionService) {
        this.versionService = versionService;
    }

    @GetMapping
    public List<VersionResponse> list(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return versionService.list(user, idOrKey);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VersionResponse create(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @Valid @RequestBody CreateVersionRequest request) {
        return versionService.create(user, idOrKey, request);
    }

    @PatchMapping("/{versionId}")
    public VersionResponse update(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long versionId, @Valid @RequestBody UpdateVersionRequest request) {
        return versionService.update(user, idOrKey, versionId, request);
    }

    @DeleteMapping("/{versionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long versionId) {
        versionService.delete(user, idOrKey, versionId);
    }

    /** Sets the order of all versions; the body must list every version id of the project once. */
    @PutMapping("/order")
    public List<VersionResponse> reorder(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey, @Valid @RequestBody VersionOrderRequest request) {
        return versionService.reorder(user, idOrKey, request.versionIds());
    }
}
