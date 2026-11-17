/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.demo;

import java.util.EnumSet;
import java.util.Set;
import org.buggywuggy.project.ProjectPermission;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Demo mode, chosen when building ({@code ./mvnw -Ddemo package}, which also names the jar
 * {@code buggywuggy-demo.jar}); it cannot be switched on or off in the configuration. The
 * build records it in {@code META-INF/build-info.properties} as {@code build.demo=true}.
 *
 * <p>In demo mode:
 * <ul>
 * <li>there is an account {@value #USERNAME} with password {@value #PASSWORD}, which anyone
 * may use; the login page fills it in;</li>
 * <li>that account may do everything with issues in the project {@value #PROJECT_KEY}, and only
 * read elsewhere; it has no global permissions and cannot change its name, e-mail address or
 * password; it needs no second factor;</li>
 * <li>other users and administrators work as usual;</li>
 * <li>BuggyWuggy sends no e-mail at all.</li>
 * </ul>
 */
@Component
public class DemoMode {

    public static final String USERNAME = "demo";
    public static final String PASSWORD = "demo";
    public static final String DISPLAY_NAME = "Demo user";
    /** Never delivered: {@code .invalid} is reserved (RFC 2606), and demo mode sends no mail anyway. */
    public static final String EMAIL = "demo@buggywuggy.invalid";
    public static final String PROJECT_KEY = "DEMO";
    public static final String PROJECT_NAME = "Demo";

    private final boolean enabled;

    @Autowired
    public DemoMode(ObjectProvider<BuildProperties> build) {
        this(build.getIfAvailable() != null && "true".equals(build.getIfAvailable().get("demo")));
    }

    public DemoMode(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean isDemoUser(String username) {
        return enabled && USERNAME.equalsIgnoreCase(username);
    }

    public boolean isDemoUser(BuggyWuggyPrincipal user) {
        return user != null && isDemoUser(user.getUsername());
    }

    /**
     * The permissions of the demo account in a project: everything except administering the
     * project in {@value #PROJECT_KEY}; elsewhere at most browsing, as its permission scheme
     * allows.
     */
    public Set<ProjectPermission> demoPermissions(String projectKey, Set<ProjectPermission> granted) {
        if (PROJECT_KEY.equals(projectKey)) {
            Set<ProjectPermission> all = EnumSet.allOf(ProjectPermission.class);
            all.remove(ProjectPermission.ADMINISTER_PROJECT);
            return all;
        }
        return granted.contains(ProjectPermission.BROWSE_PROJECT)
                ? EnumSet.of(ProjectPermission.BROWSE_PROJECT)
                : EnumSet.noneOf(ProjectPermission.class);
    }
}
