/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.board;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.issue.IssueDtos.IssueRef;
import org.buggywuggy.issue.IssueDtos.IssueTypeRef;
import org.buggywuggy.issue.IssueDtos.PriorityRef;
import org.buggywuggy.issue.IssueDtos.ProjectRef;
import org.buggywuggy.issue.IssueDtos.StatusRef;
import org.buggywuggy.user.UserDtos.UserRef;

public final class BoardDtos {

    private BoardDtos() {
    }

    /** How a board is divided into horizontal lanes. */
    public enum Swimlanes {
        NONE, ASSIGNEE, EPIC, PRIORITY
    }

    // --- configuration ---------------------------------------------------------------

    public record ColumnConfig(long id, String name, List<Long> statusIds) {
    }

    /** {@code own}: the project has its own columns; otherwise it uses the default board. */
    public record BoardConfigResponse(boolean own, Swimlanes swimlanes, int resolvedDays, List<ColumnConfig> columns) {
    }

    public record ColumnRequest(@NotBlank @Size(max = 100) String name, @NotNull List<@NotNull Long> statusIds) {
    }

    public record BoardConfigRequest(
            @NotNull Swimlanes swimlanes,
            @NotNull @Min(0) @Max(365) Integer resolvedDays,
            @NotNull @Size(min = 1, max = 20) List<@NotNull @Valid ColumnRequest> columns) {
    }

    // --- the board -------------------------------------------------------------------

    public record BoardColumn(long id, String name, List<StatusRef> statuses) {
    }

    /**
     * An issue on the board. {@code epic}: the epic it belongs to (for a sub-task, that of its
     * parent; for an epic, itself). {@code columnId}: the column of its status.
     */
    public record BoardIssue(long id, String key, String summary, IssueTypeRef issueType, StatusRef status,
            @ApiNullable PriorityRef priority, @ApiNullable UserRef assignee, @ApiNullable IssueRef parent,
            @ApiNullable IssueRef epic, @ApiNullable Double urgency, long version, long columnId) {
    }

    /** What the current user may do on the board. */
    public record BoardPermissions(boolean transition, boolean assign, boolean edit, boolean configure) {
    }

    /**
     * The board of a project: its columns, and its issues in board order (unresolved ones, and
     * those resolved in the last {@code resolvedDays} days). {@code unmappedStatuses}: statuses
     * of the project's workflows that are in no column, so their issues are not shown.
     */
    public record BoardResponse(ProjectRef project, boolean ownColumns, Swimlanes swimlanes, int resolvedDays,
            List<BoardColumn> columns, List<BoardIssue> issues, boolean truncated, List<StatusRef> unmappedStatuses,
            BoardPermissions permissions) {
    }

    // --- moving an issue -------------------------------------------------------------

    /**
     * A lane of a board: {@code value} is the user id (ASSIGNEE), the epic's key (EPIC) or the
     * priority id (PRIORITY); null is the lane without (unassigned, no epic).
     */
    public record Lane(@NotNull Swimlanes by, @ApiNullable String value) {
    }

    /**
     * Moves an issue on the board, all or nothing: {@code transitionId} to another column (with
     * {@code resolutionId} for a done status), {@code lane} to another lane (changes the field of
     * the lane), and {@code after}/{@code before} the issues it is placed between (keys; one may
     * be null at the start or end of a column). Each part is optional.
     */
    public record MoveRequest(@NotBlank String issue, Long version, Long transitionId, Long resolutionId,
            @Valid Lane lane, String after, String before) {
    }
}
