/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import jakarta.validation.Valid;
import org.buggywuggy.license.FeedbackService.FeedbackRequest;
import org.buggywuggy.license.FeedbackService.FeedbackResponse;
import org.buggywuggy.license.FeedbackService.FeedbackStatus;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Help → Send feedback: for every user of an installation with a valid donation. */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    /** Whether this user may send feedback, and whether BuggyWuggy can send it by mail. */
    @GetMapping
    public FeedbackStatus feedbackStatus(@AuthenticationPrincipal BuggyWuggyPrincipal user) {
        return feedback.status(user);
    }

    /** Mails the feedback to feedback@buggywuggy.org; 403 without a valid donation. */
    @PostMapping
    public FeedbackResponse sendFeedback(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @Valid @RequestBody FeedbackRequest request) {
        return feedback.send(user, request);
    }
}
