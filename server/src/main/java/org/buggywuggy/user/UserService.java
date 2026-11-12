/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.forbidden;
import static org.buggywuggy.common.ApiException.notFound;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import org.buggywuggy.common.PageResponse;
import org.buggywuggy.common.SqlLike;
import org.buggywuggy.notification.NotificationPreferences;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.security.SessionInvalidator;
import org.buggywuggy.user.UserDtos.CreateUserRequest;
import org.buggywuggy.user.UserDtos.MyselfResponse;
import org.buggywuggy.user.UserDtos.UpdateMyselfRequest;
import org.buggywuggy.user.UserDtos.UpdateUserRequest;
import org.buggywuggy.user.UserDtos.UserResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserService {

    private final NotificationPreferences notifications;

    private final UserAccountRepository users;
    private final UserGroupRepository groups;
    private final PasswordEncoder passwordEncoder;
    private final SessionInvalidator sessions;
    private final Clock clock;

    public UserService(UserAccountRepository users, UserGroupRepository groups, PasswordEncoder passwordEncoder,
            SessionInvalidator sessions, Clock clock, NotificationPreferences notifications) {
        this.notifications = notifications;
        this.users = users;
        this.groups = groups;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.clock = clock;
    }

    // --- administration ------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(String query, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<UserAccount> result = query == null || query.isBlank()
                ? users.findAllRegular(pageable)
                : users.searchRegular(SqlLike.containsPattern(query), pageable);
        return PageResponse.of(result, UserResponse::of);
    }

    @Transactional(readOnly = true)
    public UserResponse get(long id) {
        return UserResponse.of(find(id));
    }

    public UserResponse create(CreateUserRequest request, BuggyWuggyPrincipal actor) {
        boolean systemAdmin = Boolean.TRUE.equals(request.systemAdmin());
        if (systemAdmin && !actor.isSystemAdmin()) {
            throw forbidden("user.system-admin-required");
        }
        UserAccount user = createUser(request.username(), request.displayName(), request.email(),
                request.password(), systemAdmin, List.of(UserGroup.USERS));
        return UserResponse.of(user);
    }

    /** Creates a regular (non-placeholder) user and adds it to the named groups that exist. */
    public UserAccount createUser(String username, String displayName, String email, String rawPassword,
            boolean systemAdmin, Collection<String> groupNames) {
        String name = username.trim();
        String mail = email.trim();
        if (users.existsByUsername(name)) {
            throw conflict("user.username-taken", name);
        }
        if (users.existsByEmailExcluding(mail, -1)) {
            throw conflict("user.email-taken", mail);
        }
        UserAccount user = new UserAccount(name, displayName.trim(), mail, clock.instant());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setSystemAdmin(systemAdmin);
        groupNames.forEach(g -> groups.findByName(g).ifPresent(user.getGroups()::add));
        return users.save(user);
    }

    public UserResponse update(long id, UpdateUserRequest request, BuggyWuggyPrincipal actor) {
        UserAccount user = find(id);
        requireMayManage(user, actor);
        boolean endSessions = false;

        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.email() != null) {
            changeEmail(user, request.email());
        }
        if (request.locale() != null) {
            user.setLocale(blankToNull(request.locale()));
        }
        if (request.timeZone() != null) {
            user.setTimeZone(validTimeZone(request.timeZone()));
        }
        if (request.active() != null && request.active() != user.isActive()) {
            if (!request.active()) {
                if (user.getId() == actor.id()) {
                    throw forbidden("user.cannot-deactivate-self");
                }
                if (user.isSystemAdmin()) {
                    requireAnotherActiveSystemAdmin();
                }
                endSessions = true;
            }
            user.setActive(request.active());
        }
        if (request.systemAdmin() != null && request.systemAdmin() != user.isSystemAdmin()) {
            if (!actor.isSystemAdmin()) {
                throw forbidden("user.system-admin-required");
            }
            if (!request.systemAdmin()) {
                if (user.getId() == actor.id()) {
                    throw forbidden("user.cannot-revoke-own-admin");
                }
                if (user.isActive()) {
                    requireAnotherActiveSystemAdmin();
                }
            }
            user.setSystemAdmin(request.systemAdmin());
            endSessions = true;
        }
        if (endSessions) {
            sessions.invalidateAll(user.getUsername());
        }
        return UserResponse.of(user);
    }

    public void setPassword(long id, String rawPassword, BuggyWuggyPrincipal actor) {
        UserAccount user = find(id);
        requireMayManage(user, actor);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        sessions.invalidateAll(user.getUsername());
    }

    // --- the logged-in user ----------------------------------------------------------

    @Transactional(readOnly = true)
    public MyselfResponse myself(BuggyWuggyPrincipal principal) {
        return toMyself(find(principal.id()), principal);
    }

    public MyselfResponse updateMyself(BuggyWuggyPrincipal principal, UpdateMyselfRequest request) {
        UserAccount user = find(principal.id());
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName().trim());
        }
        if (request.email() != null) {
            changeEmail(user, request.email());
        }
        if (request.locale() != null) {
            user.setLocale(blankToNull(request.locale()));
        }
        if (request.timeZone() != null) {
            user.setTimeZone(validTimeZone(request.timeZone()));
        }
        notifications.set(user.getId(), request.emailNotifications(), request.notifyOwnChanges());
        return toMyself(user, principal);
    }

    /** Changes the user's own password and ends all their other sessions. */
    public void changeOwnPassword(BuggyWuggyPrincipal principal, String currentPassword, String newPassword,
            String currentSessionId) {
        UserAccount user = find(principal.id());
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw badRequest("auth.wrong-current-password");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        sessions.invalidateAllExcept(user.getUsername(), currentSessionId);
    }

    /** Returns the user if it exists and is active; otherwise fails with {@code messageKey}. */
    @Transactional(readOnly = true)
    public UserAccount requireActiveUser(long id, String messageKey) {
        return users.findById(id)
                .filter(u -> u.isActive() && !u.isPlaceholder())
                .orElseThrow(() -> badRequest(messageKey));
    }

    public void recordLogin(long userId) {
        users.updateLastLogin(userId, clock.instant());
    }

    // --- helpers ---------------------------------------------------------------------

    private UserAccount find(long id) {
        return users.findById(id).orElseThrow(() -> notFound("user.not-found", id));
    }

    /** Only system administrators may manage system administrators. */
    private static void requireMayManage(UserAccount target, BuggyWuggyPrincipal actor) {
        if (target.isSystemAdmin() && !actor.isSystemAdmin()) {
            throw forbidden("user.system-admin-required");
        }
    }

    private void requireAnotherActiveSystemAdmin() {
        if (users.countActiveSystemAdmins() <= 1) {
            throw conflict("user.last-system-admin");
        }
    }

    private void changeEmail(UserAccount user, String email) {
        String mail = email.trim();
        if (users.existsByEmailExcluding(mail, user.getId())) {
            throw conflict("user.email-taken", mail);
        }
        user.setEmail(mail);
    }

    private static String validTimeZone(String timeZone) {
        if (timeZone.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(timeZone.trim()).getId();
        } catch (DateTimeException e) {
            throw badRequest("validation.time-zone.invalid");
        }
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value.trim();
    }

    private MyselfResponse toMyself(UserAccount u, BuggyWuggyPrincipal principal) {
        var prefs = notifications.of(u.getId());
        return new MyselfResponse(u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.isSystemAdmin(),
                u.getLocale(), u.getTimeZone(), principal.globalPermissions(), prefs.notifications(),
                prefs.ownChanges());
    }
}
