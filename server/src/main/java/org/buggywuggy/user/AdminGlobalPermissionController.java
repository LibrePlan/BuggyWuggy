/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import java.util.List;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.GlobalPermissionService.GlobalPermissionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global permissions per group. Requires the ADMINISTER global permission (see
 * SecurityConfiguration). A change ends the sessions of the group's members.
 */
@RestController
@RequestMapping("/api/v1/admin/global-permissions")
public class AdminGlobalPermissionController {

    private final GlobalPermissionService service;

    public AdminGlobalPermissionController(GlobalPermissionService service) {
        this.service = service;
    }

    @GetMapping
    public List<GlobalPermissionResponse> list() {
        return service.list();
    }

    @PutMapping("/{permission}/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(@PathVariable String permission, @PathVariable long groupId) {
        service.grant(permission, groupId);
    }

    @DeleteMapping("/{permission}/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal BuggyWuggyPrincipal actor, @PathVariable String permission,
            @PathVariable long groupId) {
        service.revoke(actor, permission, groupId);
    }
}
