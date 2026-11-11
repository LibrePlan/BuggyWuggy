/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.issue;

import jakarta.validation.Valid;
import java.util.List;
import org.buggywuggy.issue.CollaborationDtos.CommentResponse;
import org.buggywuggy.issue.CollaborationDtos.CreateCommentRequest;
import org.buggywuggy.issue.CollaborationDtos.CreateLinkRequest;
import org.buggywuggy.issue.CollaborationDtos.IssueLinkResponse;
import org.buggywuggy.issue.CollaborationDtos.LinkTypeResponse;
import org.buggywuggy.issue.CollaborationDtos.UpdateCommentRequest;
import org.buggywuggy.issue.CollaborationDtos.WatchersResponse;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Comments, issue links and watchers. {@code idOrKey} is an issue id or (former) key. */
@RestController
public class CollaborationController {

    private final CommentService comments;
    private final IssueLinkService links;
    private final WatcherService watchers;

    public CollaborationController(CommentService comments, IssueLinkService links, WatcherService watchers) {
        this.comments = comments;
        this.links = links;
        this.watchers = watchers;
    }

    // --- comments --------------------------------------------------------------------

    @GetMapping("/api/v1/issues/{idOrKey}/comments")
    public List<CommentResponse> comments(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return comments.list(user, idOrKey);
    }

    @PostMapping("/api/v1/issues/{idOrKey}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse addComment(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey, @Valid @RequestBody CreateCommentRequest request) {
        return comments.create(user, idOrKey, request);
    }

    @PatchMapping("/api/v1/issues/{idOrKey}/comments/{commentId}")
    public CommentResponse updateComment(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey, @PathVariable long commentId,
            @Valid @RequestBody UpdateCommentRequest request) {
        return comments.update(user, idOrKey, commentId, request);
    }

    @DeleteMapping("/api/v1/issues/{idOrKey}/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long commentId) {
        comments.delete(user, idOrKey, commentId);
    }

    // --- links -----------------------------------------------------------------------

    @GetMapping("/api/v1/issue-link-types")
    public List<LinkTypeResponse> linkTypes() {
        return links.linkTypes();
    }

    @GetMapping("/api/v1/issues/{idOrKey}/links")
    public List<IssueLinkResponse> links(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return links.list(user, idOrKey);
    }

    @PostMapping("/api/v1/issues/{idOrKey}/links")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueLinkResponse addLink(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey, @Valid @RequestBody CreateLinkRequest request) {
        return links.create(user, idOrKey, request);
    }

    @DeleteMapping("/api/v1/issues/{idOrKey}/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLink(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String idOrKey,
            @PathVariable long linkId) {
        links.delete(user, idOrKey, linkId);
    }

    // --- watchers --------------------------------------------------------------------

    @GetMapping("/api/v1/issues/{idOrKey}/watchers")
    public WatchersResponse watchers(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey) {
        return watchers.get(user, idOrKey);
    }

    /** Adds a watcher: yourself, or (with MANAGE_WATCHERS) another user who can browse the project. */
    @PutMapping("/api/v1/issues/{idOrKey}/watchers/{userId}")
    public WatchersResponse addWatcher(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey, @PathVariable long userId) {
        return watchers.add(user, idOrKey, userId);
    }

    @DeleteMapping("/api/v1/issues/{idOrKey}/watchers/{userId}")
    public WatchersResponse removeWatcher(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            @PathVariable String idOrKey, @PathVariable long userId) {
        return watchers.remove(user, idOrKey, userId);
    }
}
