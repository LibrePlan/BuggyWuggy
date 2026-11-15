/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.buggywuggy.common.PageResponse;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserDtos.CreateUserRequest;
import org.buggywuggy.user.UserDtos.SetPasswordRequest;
import org.buggywuggy.user.UserDtos.UpdateUserRequest;
import org.buggywuggy.user.UserDtos.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** User administration. Requires the ADMINISTER global permission (see SecurityConfiguration). */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final UserService userService;

    public AdminUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return userService.search(query, page, size);
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal BuggyWuggyPrincipal actor) {
        UserResponse created = userService.create(request, actor);
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable long id) {
        return userService.get(id);
    }

    @PatchMapping("/{id}")
    public UserResponse update(@PathVariable long id, @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal BuggyWuggyPrincipal actor) {
        return userService.update(id, request, actor);
    }

    @PutMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setPassword(@PathVariable long id, @Valid @RequestBody SetPasswordRequest request,
            @AuthenticationPrincipal BuggyWuggyPrincipal actor) {
        userService.setPassword(id, request.password(), actor);
    }

    /** Forgets the user's second factor: the next login sets up two-factor login again. */
    @DeleteMapping("/{id}/two-factor")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetTwoFactor(@PathVariable long id, @AuthenticationPrincipal BuggyWuggyPrincipal actor) {
        userService.resetTwoFactor(id, actor);
    }
}
