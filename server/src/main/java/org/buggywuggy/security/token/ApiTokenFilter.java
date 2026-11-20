/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.token;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.security.Authorities;
import org.buggywuggy.security.token.ApiTokenService.TokenUse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Logs in a request with {@code Authorization: Bearer bw_...} as the token's user, for this
 * request only: no session is made. A wrong, expired or removed token is refused (401) rather
 * than treated as not logged in; a read-only token gets 403 for anything but reading.
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    /** Requests that carry a token: they need no CSRF token, as a browser never adds one itself. */
    public static final RequestMatcher BEARER = request -> bearer(request).isPresent();

    private static final Set<String> READING = Set.of("GET", "HEAD", "OPTIONS");

    private final ApiTokenService tokens;
    private final HandlerExceptionResolver exceptionResolver;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public ApiTokenFilter(ApiTokenService tokens, HandlerExceptionResolver exceptionResolver) {
        this.tokens = tokens;
        this.exceptionResolver = exceptionResolver;
    }

    static Optional<String> bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(7).strip());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> secret = bearer(request);
        if (secret.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<TokenUse> use = tokens.authenticate(secret.get());
        if (use.isEmpty()) {
            refuse(request, response, new ApiException(HttpStatus.UNAUTHORIZED, "token.invalid"));
            return;
        }
        if (use.get().readOnly() && !READING.contains(request.getMethod())) {
            refuse(request, response, new ApiException(HttpStatus.FORBIDDEN, "token.read-only"));
            return;
        }
        List<GrantedAuthority> authorities = new ArrayList<>(use.get().principal().getAuthorities());
        authorities.add(new SimpleGrantedAuthority(Authorities.API_TOKEN));
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(use.get().principal(), null, authorities));
        contextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            contextHolder.clearContext();
        }
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, ApiException error) {
        exceptionResolver.resolveException(request, response, null, error);
    }
}
