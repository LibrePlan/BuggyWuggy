/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.search;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.buggywuggy.common.PageResponse;
import org.buggywuggy.issue.IssueDtos.IssueSummaryResponse;
import org.buggywuggy.project.ProjectPermissionService;
import org.buggywuggy.search.SavedFilterDtos.CreateFilterRequest;
import org.buggywuggy.search.SavedFilterDtos.FilterResponse;
import org.buggywuggy.search.SavedFilterDtos.UpdateFilterRequest;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.common.SqlLike;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Issue search, saved filters and label suggestions. */
@RestController
public class SearchController {

    private final IssueSearchService search;
    private final SavedFilterService filters;
    private final NamedParameterJdbcTemplate jdbc;

    public SearchController(IssueSearchService search, SavedFilterService filters, NamedParameterJdbcTemplate jdbc) {
        this.search = search;
        this.filters = filters;
        this.jdbc = jdbc;
    }

    /**
     * Searches issues. A POST, because a query has many optional parts and is the same JSON
     * object that saved filters store.
     */
    @PostMapping("/api/v1/issues/search")
    public PageResponse<IssueSummaryResponse> search(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @Valid @RequestBody IssueQuery query,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return search.search(user, query, page, size);
    }

    /** Labels used in projects the user can browse that contain {@code query}. */
    @GetMapping("/api/v1/labels")
    public List<String> labels(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return jdbc.queryForList("""
                select distinct l.label from issue_label l
                join issue i on i.id = l.issue_id
                join project p on p.id = i.project_id
                where lower(l.label) like :pattern escape '\\'
                  and """ + " " + ProjectPermissionService.PROJECT_BROWSABLE + """
                 order by l.label limit :limit""",
                Map.of("pattern", SqlLike.containsPattern(query == null ? "" : query), "userId", user.id(), "limit", limit),
                String.class);
    }

    // --- saved filters ---------------------------------------------------------------

    @GetMapping("/api/v1/filters")
    public List<FilterResponse> filters(@AuthenticationPrincipal BuggyWuggyPrincipal user) {
        return filters.list(user);
    }

    @GetMapping("/api/v1/filters/{id}")
    public FilterResponse filter(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable long id) {
        return filters.get(user, id);
    }

    @PostMapping("/api/v1/filters")
    @ResponseStatus(HttpStatus.CREATED)
    public FilterResponse createFilter(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @Valid @RequestBody CreateFilterRequest request) {
        return filters.create(user, request);
    }

    @PatchMapping("/api/v1/filters/{id}")
    public FilterResponse updateFilter(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable long id,
            @Valid @RequestBody UpdateFilterRequest request) {
        return filters.update(user, id, request);
    }

    @DeleteMapping("/api/v1/filters/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFilter(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable long id) {
        filters.delete(user, id);
    }
}
