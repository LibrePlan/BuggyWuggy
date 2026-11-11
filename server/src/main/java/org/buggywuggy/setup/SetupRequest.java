/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.setup;

import static org.buggywuggy.user.UserDtos.PASSWORD_MAX;
import static org.buggywuggy.user.UserDtos.PASSWORD_MIN;
import static org.buggywuggy.user.UserDtos.USERNAME_PATTERN;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SetupRequest(
        @NotBlank @Size(max = 200) String setupToken,
        @NotBlank @Pattern(regexp = USERNAME_PATTERN, message = "{validation.username.pattern}") String username,
        @NotBlank @Size(max = 255) String displayName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotNull @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = "{validation.password.size}") String password) {
}
