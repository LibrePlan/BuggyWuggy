/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.issue.IssueDtos.IssueTypeRef;
import org.buggywuggy.issue.IssueDtos.StatusRef;
import org.buggywuggy.user.UserDtos.UserRef;

/** Requests and responses for comments, issue links and watchers. */
public final class CollaborationDtos {

    private CollaborationDtos() {
    }

    public static final int COMMENT_MAX = 100_000;

    // --- comments --------------------------------------------------------------------

    /**
     * A comment. {@code body} is Markdown. {@code canEdit} / {@code canDelete} tell whether the
     * current user may change it.
     */
    public record CommentResponse(long id, @ApiNullable UserRef author, String body, Instant createdAt,
            Instant updatedAt, @ApiNullable UserRef updateAuthor, long version, boolean canEdit, boolean canDelete) {
    }

    public record CreateCommentRequest(@NotBlank @Size(max = COMMENT_MAX) String body) {
    }

    /** When {@code version} is given, the update fails with 409 if the comment changed in the meantime. */
    public record UpdateCommentRequest(@NotBlank @Size(max = COMMENT_MAX) String body, Long version) {
    }

    // --- links -----------------------------------------------------------------------

    public enum LinkDirection { OUTWARD, INWARD }

    public record LinkTypeResponse(long id, String name, String outward, String inward) {
    }

    /** An issue at the other end of a link. */
    public record LinkedIssueRef(long id, String key, String summary, IssueTypeRef issueType, StatusRef status) {
    }

    /**
     * A link seen from one issue: {@code description} is the phrase from that issue's point of
     * view, e.g. "blocks" (outward) or "is blocked by" (inward).
     */
    public record IssueLinkResponse(long id, LinkTypeResponse type, LinkDirection direction, String description,
            LinkedIssueRef issue) {
    }

    /**
     * Links this issue to {@code issue} (key or id). With direction {@code OUTWARD} (default)
     * this issue is the source ("this issue blocks the other one"); with {@code INWARD} the
     * other issue is the source.
     */
    public record CreateLinkRequest(@NotNull Long linkTypeId, @NotBlank String issue, LinkDirection direction) {
    }

    // --- watchers --------------------------------------------------------------------

    /**
     * {@code watchers} lists the watching users; it is {@code null} when the current user may
     * not see who watches (requires MANAGE_WATCHERS), in which case only the count is given.
     */
    public record WatchersResponse(boolean watching, int count, @ApiNullable List<UserRef> watchers) {
    }
}
