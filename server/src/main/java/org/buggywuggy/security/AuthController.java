/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.user.UserDtos.MyselfResponse;
import org.buggywuggy.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-based login for the web UI. Logout is handled by Spring Security at
 * {@code POST /api/v1/auth/logout}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 256) String password) {
    }

    public record CsrfResponse(String headerName, String token) {
    }

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final LoginThrottle throttle;
    private final UserService userService;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository, LoginThrottle throttle, UserService userService) {
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.throttle = throttle;
        this.userService = userService;
    }

    /** Returns the CSRF token (and sets the XSRF-TOKEN cookie) for clients that need it up front. */
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public MyselfResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String throttleKey = LoginThrottle.key(request.username(), httpRequest.getRemoteAddr());
        if (throttle.isBlocked(throttleKey)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "auth.throttled");
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username().trim(), request.password()));
        } catch (AuthenticationException e) {
            // Unknown user, wrong password and deactivated account all get the same answer,
            // so the response does not reveal which accounts exist.
            throttle.recordFailure(throttleKey);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "auth.bad-credentials");
        }
        throttle.recordSuccess(throttleKey);

        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        BuggyWuggyPrincipal principal = (BuggyWuggyPrincipal) authentication.getPrincipal();
        userService.recordLogin(principal.id());
        return userService.myself(principal);
    }
}
