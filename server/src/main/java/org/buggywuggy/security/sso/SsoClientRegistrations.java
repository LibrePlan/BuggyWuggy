/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.buggywuggy.security.sso.SsoProperties.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistration.ClientSettings;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * The configured providers as Spring Security client registrations. Each provider's
 * discovery document is read at its first use and kept, so BuggyWuggy starts when an identity
 * provider is down; until it can be reached, its login button gives an error and BuggyWuggy
 * tries again at most once a minute.
 */
public class SsoClientRegistrations implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(SsoClientRegistrations.class);
    private static final Duration RETRY_AFTER = Duration.ofMinutes(1);
    /** Spring Security fills in the address the browser used, so it works behind a reverse proxy. */
    static final String REDIRECT_URI = "{baseUrl}/login/oauth2/code/{registrationId}";

    private final SsoProperties properties;
    private final Clock clock;
    private final Map<String, ClientRegistration> registrations = new ConcurrentHashMap<>();
    private final Map<String, Instant> failedAt = new ConcurrentHashMap<>();

    public SsoClientRegistrations(SsoProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ClientRegistration findByRegistrationId(String id) {
        Provider provider = properties.providers().get(id);
        if (provider == null) {
            return null;
        }
        ClientRegistration known = registrations.get(id);
        if (known != null) {
            return known;
        }
        Instant failed = failedAt.get(id);
        if (failed != null && clock.instant().isBefore(failed.plus(RETRY_AFTER))) {
            return null;
        }
        try {
            ClientRegistration registration = discover(id, provider);
            registrations.put(id, registration);
            failedAt.remove(id);
            return registration;
        } catch (RuntimeException e) {
            failedAt.put(id, clock.instant());
            log.warn("Single sign-on provider '{}' cannot be reached at {}: {}", id, provider.issuerUri(),
                    e.getMessage());
            return null;
        }
    }

    private static ClientRegistration discover(String id, Provider provider) {
        boolean publicClient = provider.clientSecret().isBlank();
        // The discovery document chooses how the client authenticates (usually client_secret_basic).
        ClientRegistration.Builder builder = ClientRegistrations.fromIssuerLocation(provider.issuerUri());
        if (publicClient) {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }
        return builder
                .registrationId(id)
                .clientName(provider.name(id))
                .clientId(provider.clientId())
                .clientSecret(publicClient ? null : provider.clientSecret())
                .scope(provider.scopes())
                .redirectUri(REDIRECT_URI)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                // PKCE also for confidential clients (OAuth 2.0 Security Best Current Practice).
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .build();
    }
}
