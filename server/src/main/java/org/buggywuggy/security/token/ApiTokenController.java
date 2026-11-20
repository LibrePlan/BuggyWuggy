/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.token;

import static org.buggywuggy.common.ApiException.forbidden;

import jakarta.validation.Valid;
import java.util.List;
import org.buggywuggy.security.Authorities;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.security.token.ApiTokenService.ApiTokenResponse;
import org.buggywuggy.security.token.ApiTokenService.CreateApiTokenRequest;
import org.buggywuggy.security.token.ApiTokenService.CreatedApiTokenResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Personal API tokens: one's own under /myself, any user's for administrators. Tokens are
 * made and removed only in a logged-in session, never with a token.
 */
@RestController
public class ApiTokenController {

    private final ApiTokenService tokens;

    public ApiTokenController(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    private static void requireSession(Authentication authentication) {
        if (authentication.getAuthorities().stream().anyMatch(a -> Authorities.API_TOKEN.equals(a.getAuthority()))) {
            throw forbidden("token.session-required");
        }
    }

    @GetMapping("/api/v1/myself/tokens")
    public List<ApiTokenResponse> myTokens(@AuthenticationPrincipal BuggyWuggyPrincipal user) {
        return tokens.list(user.id());
    }

    @PostMapping("/api/v1/myself/tokens")
    public CreatedApiTokenResponse createToken(@AuthenticationPrincipal BuggyWuggyPrincipal user,
            Authentication authentication, @Valid @RequestBody CreateApiTokenRequest request) {
        requireSession(authentication);
        return tokens.create(user, request);
    }

    @DeleteMapping("/api/v1/myself/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMyToken(@AuthenticationPrincipal BuggyWuggyPrincipal user, Authentication authentication,
            @PathVariable long id) {
        requireSession(authentication);
        tokens.delete(user.id(), id);
    }

    /** The tokens of a user, for administrators (/api/v1/admin/** needs ADMINISTER). */
    @GetMapping("/api/v1/admin/users/{userId}/tokens")
    public List<ApiTokenResponse> tokensOfUser(@PathVariable long userId) {
        return tokens.list(userId);
    }

    @DeleteMapping("/api/v1/admin/users/{userId}/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTokenOfUser(Authentication authentication, @PathVariable long userId, @PathVariable long id) {
        requireSession(authentication);
        tokens.delete(userId, id);
    }
}
