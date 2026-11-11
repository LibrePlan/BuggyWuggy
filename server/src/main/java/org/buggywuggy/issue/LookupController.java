/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import java.util.List;
import org.buggywuggy.issue.IssueDtos.IssueTypeResponse;
import org.buggywuggy.issue.IssueDtos.PriorityResponse;
import org.buggywuggy.issue.IssueDtos.ResolutionResponse;
import org.buggywuggy.issue.IssueDtos.StatusResponse;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Global lookup lists used when creating and editing issues. */
@RestController
@RequestMapping("/api/v1")
@Transactional(readOnly = true)
public class LookupController {

    private final IssueTypeRepository issueTypes;
    private final PriorityRepository priorities;
    private final StatusRepository statuses;
    private final ResolutionRepository resolutions;

    public LookupController(IssueTypeRepository issueTypes, PriorityRepository priorities,
            StatusRepository statuses, ResolutionRepository resolutions) {
        this.issueTypes = issueTypes;
        this.priorities = priorities;
        this.statuses = statuses;
        this.resolutions = resolutions;
    }

    @GetMapping("/issue-types")
    public List<IssueTypeResponse> issueTypes() {
        return issueTypes.findAllOrdered().stream().map(IssueTypeResponse::of).toList();
    }

    @GetMapping("/priorities")
    public List<PriorityResponse> priorities() {
        return priorities.findAllOrdered().stream().map(PriorityResponse::of).toList();
    }

    @GetMapping("/statuses")
    public List<StatusResponse> statuses() {
        return statuses.findAllOrdered().stream().map(StatusResponse::of).toList();
    }

    @GetMapping("/resolutions")
    public List<ResolutionResponse> resolutions() {
        return resolutions.findAllOrdered().stream().map(ResolutionResponse::of).toList();
    }
}
