/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.demo;

import java.util.List;
import java.util.Set;
import org.buggywuggy.project.ProjectDtos.CreateProjectRequest;
import org.buggywuggy.project.ProjectService;
import org.buggywuggy.security.Authorities;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.user.UserAccount;
import org.buggywuggy.user.UserAccountRepository;
import org.buggywuggy.user.UserGroup;
import org.buggywuggy.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * In demo mode, creates the demo account and the demo project at start-up when they do not
 * exist. Existing ones are left as they are (an administrator may have changed them).
 */
@Component
public class DemoSetup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSetup.class);

    private final DemoMode demo;
    private final UserAccountRepository users;
    private final UserService userService;
    private final ProjectService projects;

    public DemoSetup(DemoMode demo, UserAccountRepository users, UserService userService, ProjectService projects) {
        this.demo = demo;
        this.users = users;
        this.userService = userService;
        this.projects = projects;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo.enabled()) {
            return;
        }
        log.warn("BuggyWuggy runs in DEMO MODE: account '{}' (password '{}') may use project {}; no e-mail is sent",
                DemoMode.USERNAME, DemoMode.PASSWORD, DemoMode.PROJECT_KEY);
        try {
            ensure();
        } catch (RuntimeException e) {
            log.warn("Could not set up the demo account or project: {}", e.getMessage());
        }
    }

    /** Creates the demo account and project when missing. */
    @Transactional
    public void ensure() {
        UserAccount user = users.findByUsername(DemoMode.USERNAME).orElseGet(() -> {
            log.info("Creating the demo account '{}'", DemoMode.USERNAME);
            return userService.createUser(DemoMode.USERNAME, DemoMode.DISPLAY_NAME, DemoMode.EMAIL,
                    DemoMode.PASSWORD, false, List.of(UserGroup.USERS));
        });
        if (user.isPlaceholder() || user.getPasswordHash() == null) {
            log.warn("The account '{}' cannot log in with a password (placeholder or single sign-on only)",
                    DemoMode.USERNAME);
        }
        if (projects.find(DemoMode.PROJECT_KEY).isEmpty()) {
            log.info("Creating the demo project {}", DemoMode.PROJECT_KEY);
            var creator = new BuggyWuggyPrincipal(user.getId(), user.getUsername(), user.getDisplayName(), null, true,
                    Set.of(Authorities.CREATE_PROJECT));
            projects.create(creator, new CreateProjectRequest(DemoMode.PROJECT_KEY, DemoMode.PROJECT_NAME,
                    "A playground: as the demo user you may create, change and delete issues here.",
                    user.getId(), null));
        }
    }
}
