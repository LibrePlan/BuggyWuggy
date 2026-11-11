/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.project;

/** Project-level permissions, granted through a project's permission scheme. */
public enum ProjectPermission {
    BROWSE_PROJECT,
    CREATE_ISSUE,
    EDIT_ISSUE,
    DELETE_ISSUE,
    ASSIGN_ISSUE,
    ASSIGNABLE_USER,
    TRANSITION_ISSUE,
    ADD_COMMENT,
    EDIT_ALL_COMMENTS,
    EDIT_OWN_COMMENTS,
    DELETE_ALL_COMMENTS,
    DELETE_OWN_COMMENTS,
    CREATE_ATTACHMENT,
    DELETE_ALL_ATTACHMENTS,
    DELETE_OWN_ATTACHMENTS,
    LINK_ISSUE,
    MANAGE_WATCHERS,
    ADMINISTER_PROJECT
}
