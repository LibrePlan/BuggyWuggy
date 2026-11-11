/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.setup;

import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.forbidden;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.buggywuggy.user.UserAccount;
import org.buggywuggy.user.UserDtos.UserResponse;
import org.buggywuggy.user.UserAccountRepository;
import org.buggywuggy.user.UserGroup;
import org.buggywuggy.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * First-run setup. While no active system administrator exists, the first one can be created
 * with a one-time setup token. The token is either configured ({@code BUGGYWUGGY_SETUP_TOKEN})
 * or generated and written to the server log, so only someone with access to the server can
 * claim a fresh installation.
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);
    private static final long SETUP_LOCK_ID = 0x6275_6767_7900L; // arbitrary, fixed advisory lock id

    private final UserAccountRepository users;
    private final UserService userService;
    private final JdbcTemplate jdbc;
    private final String configuredToken;
    private String generatedToken;

    public SetupService(UserAccountRepository users, UserService userService, JdbcTemplate jdbc,
            @Value("${buggywuggy.setup.token:}") String configuredToken) {
        this.users = users;
        this.userService = userService;
        this.jdbc = jdbc;
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
    }

    @Transactional(readOnly = true)
    public boolean isSetupRequired() {
        return users.countActiveSystemAdmins() == 0;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announceSetup() {
        if (isSetupRequired()) {
            String token = currentToken();
            if (configuredToken.isEmpty()) {
                log.warn("""

                        ==========================================================================
                          BuggyWuggy has not been set up yet.
                          Create the first administrator using this setup token:

                              {}

                        ==========================================================================""", token);
            } else {
                log.warn("BuggyWuggy has not been set up yet. Use the configured setup token "
                        + "(BUGGYWUGGY_SETUP_TOKEN) to create the first administrator.");
            }
        }
    }

    @Transactional
    public UserResponse createInitialAdministrator(SetupRequest request) {
        // Serialise concurrent setup attempts, so at most one can succeed.
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> null, SETUP_LOCK_ID);
        if (!isSetupRequired()) {
            throw conflict("setup.already-completed");
        }
        if (!tokenMatches(request.setupToken())) {
            throw forbidden("setup.invalid-token");
        }
        UserAccount admin = userService.createUser(request.username(), request.displayName(), request.email(),
                request.password(), true, List.of(UserGroup.ADMINISTRATORS, UserGroup.USERS));
        log.info("Setup completed: system administrator '{}' created.", admin.getUsername());
        return UserResponse.of(admin);
    }

    private synchronized String currentToken() {
        if (!configuredToken.isEmpty()) {
            return configuredToken;
        }
        if (generatedToken == null) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            generatedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        return generatedToken;
    }

    private boolean tokenMatches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                currentToken().getBytes(StandardCharsets.UTF_8),
                candidate.trim().getBytes(StandardCharsets.UTF_8));
    }
}
