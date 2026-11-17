/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.buggywuggy.security.BuggyWuggyUserDetailsService;
import org.buggywuggy.user.UserService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SsoProperties.class)
public class SsoConfiguration {

    /**
     * BuggyWuggy does not call the provider's APIs after the login, so it keeps no access or
     * refresh tokens (Spring Security would store them in the session).
     */
    public static final OAuth2AuthorizedClientRepository NO_AUTHORIZED_CLIENTS = new OAuth2AuthorizedClientRepository() {
        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId,
                Authentication principal, HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
        }

        @Override
        public void removeAuthorizedClient(String clientRegistrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {
        }
    };

    @Bean
    SsoClientRegistrations ssoClientRegistrations(SsoProperties properties, Clock clock) {
        return new SsoClientRegistrations(properties, clock);
    }

    @Bean
    SsoLoginHandlers ssoLoginHandlers(SsoProperties properties, SsoClientRegistrations registrations,
            BuggyWuggyUserDetailsService userDetails, UserService userService,
            SecurityContextRepository securityContextRepository) {
        return new SsoLoginHandlers(properties, registrations, userDetails, userService, securityContextRepository);
    }
}
