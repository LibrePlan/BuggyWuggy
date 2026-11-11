/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserDtos.ChangeOwnPasswordRequest;
import org.buggywuggy.user.UserDtos.MyselfResponse;
import org.buggywuggy.user.UserDtos.UpdateMyselfRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The logged-in user's own profile. */
@RestController
@RequestMapping("/api/v1/myself")
public class MyselfController {

    private final UserService userService;

    public MyselfController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public MyselfResponse get(@AuthenticationPrincipal BuggyWuggyPrincipal principal) {
        return userService.myself(principal);
    }

    @PatchMapping
    public MyselfResponse update(@AuthenticationPrincipal BuggyWuggyPrincipal principal,
            @Valid @RequestBody UpdateMyselfRequest request) {
        return userService.updateMyself(principal, request);
    }

    /** Changes the password; all other sessions of this user are ended. */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal BuggyWuggyPrincipal principal,
            @Valid @RequestBody ChangeOwnPasswordRequest request, HttpServletRequest httpRequest) {
        var session = httpRequest.getSession(false);
        userService.changeOwnPassword(principal, request.currentPassword(), request.newPassword(),
                session == null ? null : session.getId());
    }
}
