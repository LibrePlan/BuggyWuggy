/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.buggywuggy.security.Authorities;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Sessions from before two-factor login was switched on end at their next request. */
class TwoFactorSessionFilterTest {

    private static final BuggyWuggyPrincipal ALICE = new BuggyWuggyPrincipal(1, "alice", "Alice", null, true, List.of());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpSession run(boolean enabled, Authentication authentication) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authentication);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/myself");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        new TwoFactorSessionFilter(new TwoFactorSettings(enabled, "BuggyWuggy"))
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return session;
    }

    @Test
    void endsASessionWithoutSecondFactor() throws Exception {
        MockHttpSession session = run(true, UsernamePasswordAuthenticationToken.authenticated(ALICE, null, List.of()));
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void keepsASessionWithSecondFactor() throws Exception {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(ALICE, null,
                List.of(new SimpleGrantedAuthority(Authorities.TWO_FACTOR_VERIFIED)));
        MockHttpSession session = run(true, authentication);
        assertThat(session.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
    }

    @Test
    void doesNothingWhenTwoFactorLoginIsOff() throws Exception {
        MockHttpSession session = run(false, UsernamePasswordAuthenticationToken.authenticated(ALICE, null, List.of()));
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void doesNothingWithoutLogin() throws Exception {
        assertThat(run(true, null).isInvalid()).isFalse();
    }
}
