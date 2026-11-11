/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.forbidden;

import java.util.Set;
import org.buggywuggy.security.BuggyWuggyPrincipal;

/** A project together with the current user's permissions in it. */
public record ProjectAccess(Project project, BuggyWuggyPrincipal user, Set<ProjectPermission> permissions) {

    public long projectId() {
        return project.getId();
    }

    public boolean has(ProjectPermission permission) {
        return permissions.contains(permission);
    }

    public ProjectAccess require(ProjectPermission permission) {
        if (!has(permission)) {
            throw forbidden("error.forbidden");
        }
        return this;
    }

    /** Requires {@code ADMINISTER_PROJECT} and a project that is not archived. */
    public ProjectAccess requireConfigurable() {
        require(ProjectPermission.ADMINISTER_PROJECT);
        if (project.isArchived()) {
            throw conflict("project.archived", project.getProjectKey());
        }
        return this;
    }
}
