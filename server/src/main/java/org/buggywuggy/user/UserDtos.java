/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import org.buggywuggy.common.ApiNullable;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Request and response bodies of the user API. */
public final class UserDtos {

    private UserDtos() {
    }

    public static final String USERNAME_PATTERN = "^[\\p{L}\\p{N}._@+-]{2,100}$";
    public static final String LOCALE_PATTERN = "^$|^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8})*$";
    public static final int PASSWORD_MIN = 12;
    public static final int PASSWORD_MAX = 256;

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = USERNAME_PATTERN, message = "{validation.username.pattern}") String username,
            @NotBlank @Size(max = 255) String displayName,
            @NotBlank @Email @Size(max = 255) String email,
            /** Without a password the user logs in with single sign-on only (when it is configured). */
            @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = "{validation.password.size}") String password,
            Boolean systemAdmin) {
    }

    /** Partial update: {@code null} fields are left unchanged; an empty locale/time zone clears it. */
    public record UpdateUserRequest(
            @Size(min = 1, max = 255) String displayName,
            @Email @Size(min = 3, max = 255) String email,
            Boolean active,
            Boolean systemAdmin,
            @Pattern(regexp = LOCALE_PATTERN, message = "{validation.locale.pattern}") String locale,
            @Size(max = 64) String timeZone) {
    }

    /** Partial update of the logged-in user's own profile. */
    public record UpdateMyselfRequest(
            @Size(min = 1, max = 255) String displayName,
            @Email @Size(min = 3, max = 255) String email,
            @Pattern(regexp = LOCALE_PATTERN, message = "{validation.locale.pattern}") String locale,
            @Size(max = 64) String timeZone,
            Boolean emailNotifications,
            Boolean notifyOwnChanges) {
    }

    public record SetPasswordRequest(
            @NotNull @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = "{validation.password.size}") String password) {
    }

    public record ChangeOwnPasswordRequest(
            @NotNull String currentPassword,
            @NotNull @Size(min = PASSWORD_MIN, max = PASSWORD_MAX, message = "{validation.password.size}") String newPassword) {
    }

    public record GroupRef(long id, String name) {
    }

    /** Short reference to a user, embedded in other resources. */
    public record UserRef(long id, String username, String displayName) {

        public static UserRef of(UserAccount u) {
            return u == null ? null : new UserRef(u.getId(), u.getUsername(), u.getDisplayName());
        }
    }

    public record UserResponse(
            long id, String username, String displayName, @ApiNullable String email, boolean active,
            boolean systemAdmin, @ApiNullable String locale, @ApiNullable String timeZone, Instant createdAt,
            @ApiNullable Instant lastLoginAt, List<GroupRef> groups, boolean twoFactorEnrolled, boolean hasPassword) {

        public static UserResponse of(UserAccount u) {
            List<GroupRef> groups = u.getGroups().stream()
                    .map(g -> new GroupRef(g.getId(), g.getName()))
                    .sorted(Comparator.comparing(GroupRef::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            return new UserResponse(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.isActive(),
                    u.isSystemAdmin(), u.getLocale(), u.getTimeZone(), u.getCreatedAt(), u.getLastLoginAt(), groups,
                    u.isTwoFactorEnrolled(), u.getPasswordHash() != null);
        }
    }

    /** A user's identity at a single sign-on provider. */
    public record SsoIdentityResponse(String provider, String providerName, @ApiNullable String email,
            Instant createdAt, @ApiNullable Instant lastLoginAt) {
    }

    /** The logged-in user, including the global permissions of the current session. */
    public record MyselfResponse(
            long id, String username, String displayName, @ApiNullable String email, boolean systemAdmin,
            @ApiNullable String locale, @ApiNullable String timeZone, Set<String> globalPermissions,
            boolean emailNotifications, boolean notifyOwnChanges, boolean hasPassword) {
    }
}
