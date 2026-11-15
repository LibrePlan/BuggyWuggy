/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.buggywuggy.security.Authorities;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * With two-factor login enabled, ends every session that was logged in without the second
 * factor, e.g. sessions from before two-factor login was switched on. The request then
 * continues as not logged in.
 */
public class TwoFactorSessionFilter extends OncePerRequestFilter {

    private final TwoFactorSettings settings;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public TwoFactorSessionFilter(TwoFactorSettings settings) {
        this.settings = settings;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (settings.enabled() && withoutSecondFactor(contextHolder.getContext().getAuthentication())) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            contextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }

    static boolean withoutSecondFactor(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getAuthorities().stream()
                        .noneMatch(a -> Authorities.TWO_FACTOR_VERIFIED.equals(a.getAuthority()));
    }
}
