/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import java.util.Collection;
import java.util.Map;
import org.buggywuggy.security.sso.SsoAccountService.SsoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Loads the OpenID Connect user as Spring Security does (ID token, then the userinfo
 * endpoint), and then finds the BuggyWuggy account; a refused login becomes an authentication
 * error whose code the login page shows.
 */
public class SsoUserService extends OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(SsoUserService.class);

    private final SsoAccountService accounts;

    public SsoUserService(SsoAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser user = super.loadUser(request);
        String provider = request.getClientRegistration().getRegistrationId();
        try {
            return new SsoUser(user, provider, accounts.resolve(provider, user));
        } catch (SsoException e) {
            log.info("Single sign-on login refused ({}): {}", e.code(), e.getMessage());
            throw new OAuth2AuthenticationException(new OAuth2Error(e.code()), e.getMessage());
        }
    }

    /** The provider's user with the id of its BuggyWuggy account. */
    record SsoUser(OidcUser user, String provider, long accountId) implements OidcUser {

        @Override
        public Map<String, Object> getClaims() {
            return user.getClaims();
        }

        @Override
        public OidcUserInfo getUserInfo() {
            return user.getUserInfo();
        }

        @Override
        public OidcIdToken getIdToken() {
            return user.getIdToken();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return user.getAttributes();
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return user.getAuthorities();
        }

        @Override
        public String getName() {
            return user.getName();
        }
    }
}
