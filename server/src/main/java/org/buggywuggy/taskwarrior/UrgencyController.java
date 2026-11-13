/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.taskwarrior;

import java.util.List;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.issue.IssueService;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** TaskWarrior's urgency of an issue, with how it was calculated. */
@RestController
public class UrgencyController {

    /**
     * {@code urgency} is the sum of the terms' urgency, null for a resolved issue;
     * {@code terms} are the terms that count, each {@code factor * coefficient = urgency}.
     */
    public record UrgencyResponse(String key, @ApiNullable Double urgency, boolean resolved,
            List<Urgency.Term> terms) {
    }

    private final IssueService issues;
    private final UrgencyService urgency;

    public UrgencyController(IssueService issues, UrgencyService urgency) {
        this.issues = issues;
        this.urgency = urgency;
    }

    @GetMapping("/api/v1/issues/{idOrKey}/urgency")
    public UrgencyResponse get(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey) {
        IssueService.IssueAccess access = issues.access(user, idOrKey);
        Urgency.Result result = urgency.of(access.issue().getId());
        String key = access.project().project().getProjectKey() + "-" + access.issue().getNumber();
        return new UrgencyResponse(key, result.urgency(), result.urgency() == null, result.terms());
    }
}
