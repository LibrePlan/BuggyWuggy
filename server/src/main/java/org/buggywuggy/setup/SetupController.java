/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.setup;

import jakarta.validation.Valid;
import org.buggywuggy.user.UserDtos.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/setup")
public class SetupController {

    public record SetupStatus(boolean setupRequired) {
    }

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @GetMapping
    public SetupStatus status() {
        return new SetupStatus(setupService.isSetupRequired());
    }

    /** Creates the first system administrator. Only possible while setup is required. */
    @PostMapping("/admin")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createAdministrator(@Valid @RequestBody SetupRequest request) {
        return setupService.createInitialAdministrator(request);
    }
}
