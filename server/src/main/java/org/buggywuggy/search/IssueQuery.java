/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * A structured issue search, also the stored form of a saved filter. All conditions are
 * combined with AND; the values within one list with OR. Empty or {@code null} conditions
 * are ignored.
 *
 * <p>{@code assignees} and {@code reporters} contain user ids, {@code "me"} (the user running
 * the search, so a shared filter "assigned to me" works for everyone) or {@code "none"}
 * (no assignee / reporter).
 *
 * <p>{@code text} matches words in summary, description and comments, also as prefix
 * ("migr" finds "migration"), and issue keys ("BUG-12").
 *
 * <p>Unset conditions are left out of the JSON, also in stored filters.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record IssueQuery(
        @Size(max = 500) String text,
        @Size(max = 200) List<Long> projectIds,
        @Size(max = 100) List<Long> issueTypeIds,
        @Size(max = 100) List<Long> statusIds,
        @Size(max = 100) List<Long> priorityIds,
        @Size(max = 100) List<@Pattern(regexp = USER_PATTERN) String> assignees,
        @Size(max = 100) List<@Pattern(regexp = USER_PATTERN) String> reporters,
        Resolution resolution,
        @Size(max = 100) List<String> labels,
        LocalDate createdFrom,
        LocalDate createdTo,
        LocalDate updatedFrom,
        LocalDate updatedTo,
        Sort sort,
        Direction direction) {

    public static final String USER_PATTERN = "^(me|none|\\d{1,18})$";
    public static final String ME = "me";
    public static final String NONE = "none";

    public enum Resolution { UNRESOLVED, RESOLVED }

    public enum Sort { RELEVANCE, KEY, CREATED, UPDATED, PRIORITY, STATUS, DUE_DATE }

    public enum Direction { ASC, DESC }

    public static IssueQuery ofProject(long projectId) {
        return new IssueQuery(null, List.of(projectId), null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }
}
