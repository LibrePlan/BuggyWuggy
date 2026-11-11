/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.buggywuggy.common.PageResponse;
import org.buggywuggy.issue.IssueDtos.CreateIssueRequest;
import org.buggywuggy.issue.IssueDtos.IssueResponse;
import org.buggywuggy.issue.IssueDtos.IssueSummaryResponse;
import org.buggywuggy.issue.IssueDtos.UpdateIssueRequest;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** Issues. {@code idOrKey} accepts the numeric id or the issue key (e.g. {@code BUG-12}). */
@RestController
public class IssueController {

    private final IssueService issueService;

    public IssueController(IssueService issueService) {
        this.issueService = issueService;
    }

    @PostMapping("/api/v1/issues")
    public ResponseEntity<IssueResponse> create(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @Valid @RequestBody CreateIssueRequest request) {
        IssueResponse created = issueService.create(user, request);
        return ResponseEntity.created(URI.create("/api/v1/issues/" + created.key())).body(created);
    }

    @GetMapping("/api/v1/issues/{idOrKey}")
    public IssueResponse get(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey) {
        return issueService.get(user, idOrKey);
    }

    @PatchMapping("/api/v1/issues/{idOrKey}")
    public IssueResponse update(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @Valid @RequestBody UpdateIssueRequest request) {
        return issueService.update(user, idOrKey, request);
    }

    @DeleteMapping("/api/v1/issues/{idOrKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey) {
        issueService.delete(user, idOrKey);
    }

    @GetMapping("/api/v1/issues/{idOrKey}/history")
    public List<IssueHistory.Entry> history(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return issueService.history(user, idOrKey);
    }

    @GetMapping("/api/v1/projects/{projectIdOrKey}/issues")
    public PageResponse<IssueSummaryResponse> listByProject(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String projectIdOrKey,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return issueService.listByProject(user, projectIdOrKey, page, size);
    }
}
