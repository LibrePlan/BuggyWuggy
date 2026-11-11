/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GroupResponse(long id, String name, String description, int memberCount) {

    public record CreateGroupRequest(@NotBlank @Size(max = 255) String name, @Size(max = 2000) String description) {
    }

    /** Partial update: {@code null} fields are left unchanged. */
    public record UpdateGroupRequest(@Size(min = 1, max = 255) String name, @Size(max = 2000) String description) {
    }
}
