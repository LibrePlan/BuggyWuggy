/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.attachment;

import java.time.Instant;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.user.UserDtos.UserRef;

public final class AttachmentDtos {

    private AttachmentDtos() {
    }

    /**
     * An attachment. The file is at {@code /api/v1/attachments/{id}/content}. {@code previewable}:
     * an image the browser may show inline (PNG, JPEG, GIF, WebP); other files are always downloaded.
     */
    public record AttachmentResponse(long id, String fileName, String mediaType, long size,
            @ApiNullable UserRef author, Instant createdAt, boolean previewable, boolean canDelete) {
    }

    /** Upload limits, so clients can check file sizes before sending them. */
    public record AttachmentSettingsResponse(long maxFileSize, long maxRequestSize) {
    }
}
