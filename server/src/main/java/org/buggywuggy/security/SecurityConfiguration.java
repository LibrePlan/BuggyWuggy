/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security;

import java.util.List;
import java.util.Map;
import org.buggywuggy.security.sso.SsoAccountService;
import org.buggywuggy.security.sso.SsoClientRegistrations;
import org.buggywuggy.security.sso.SsoConfiguration;
import org.buggywuggy.security.sso.SsoLoginHandlers;
import org.buggywuggy.security.sso.SsoProperties;
import org.buggywuggy.security.sso.SsoUserService;
import org.buggywuggy.security.token.ApiTokenFilter;
import org.buggywuggy.security.token.ApiTokenService;
import org.buggywuggy.security.twofactor.TwoFactorSessionFilter;
import org.buggywuggy.security.twofactor.TwoFactorSettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            TwoFactorSettings twoFactorSettings,
            SsoProperties sso, SsoClientRegistrations ssoRegistrations, SsoLoginHandlers ssoHandlers,
            SsoAccountService ssoAccounts, ApiTokenService apiTokens,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/openapi/**", "/api/docs", "/api/swagger-ui/**").permitAll()
                        .requestMatchers("/api/v1/setup/**").permitAll()
                        .requestMatchers("/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/sso",
                                "/api/v1/auth/two-factor", "/api/v1/auth/two-factor/enrollment").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/donation", "/api/v1/donation/comic.png",
                                "/api/v1/donation/comic-2x.png", "/api/v1/version", "/api/v1/help/license").permitAll()
                        .requestMatchers("/api/v1/help/admin-guide")
                        .hasAnyAuthority(Authorities.SYSTEM_ADMIN, Authorities.ADMINISTER)
                        .requestMatchers("/api/v1/admin/**")
                        .hasAnyAuthority(Authorities.SYSTEM_ADMIN, Authorities.ADMINISTER)
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**", "/actuator/**").authenticated()
                        // The web UI's static files and routes; the UI itself asks the API who is logged in.
                        .anyRequest().permitAll())
                // Cookie-based CSRF protection for the browser SPA: the XSRF-TOKEN cookie must be
                // echoed in the X-XSRF-TOKEN header on every state-changing request.
                // Requests with an API token (Authorization: Bearer) have no cookies to abuse.
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository)
                        .ignoringRequestMatchers(ApiTokenFilter.BEARER))
                .securityContext(ctx -> ctx.securityContextRepository(securityContextRepository))
                // Not a bean: a filter bean would also be registered outside Spring Security.
                .addFilterAfter(new TwoFactorSessionFilter(twoFactorSettings), SecurityContextHolderFilter.class)
                // After it: a token request has no session, and needs no second factor.
                .addFilterAfter(new ApiTokenFilter(apiTokens, exceptionResolver), TwoFactorSessionFilter.class)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .addLogoutHandler(ssoHandlers.logoutHandler())
                        .logoutSuccessHandler(SsoLoginHandlers.logoutSuccessHandler()))
                // Errors are rendered as problem details by ApiExceptionHandler.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> exceptionResolver.resolveException(req, res, null, e))
                        .accessDeniedHandler((req, res, e) -> exceptionResolver.resolveException(req, res, null, e)));
        if (sso.enabled()) {
            // Single sign-on: /oauth2/authorization/<id> starts it, /login/oauth2/code/<id> ends it.
            http
                    .oauth2Login(oauth -> oauth
                            .loginPage("/login")
                            .clientRegistrationRepository(ssoRegistrations)
                            .authorizedClientRepository(SsoConfiguration.NO_AUTHORIZED_CLIENTS)
                            .userInfoEndpoint(userInfo -> userInfo.oidcUserService(new SsoUserService(ssoAccounts)))
                            .successHandler(ssoHandlers.successHandler())
                            .failureHandler(ssoHandlers.failureHandler()))
                    .addFilterBefore(ssoHandlers.startFilter(), OAuth2AuthorizationRequestRedirectFilter.class);
        }
        return http.build();
    }

    /**
     * Argon2id for new hashes. Hashes are stored with an {@code {id}} prefix, so hashes in
     * other formats (e.g. imported from another system) keep working and can be upgraded.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        String defaultId = "argon2";
        return new DelegatingPasswordEncoder(defaultId, Map.of(
                defaultId, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                "bcrypt", new BCryptPasswordEncoder()));
    }

    @Bean
    AuthenticationManager authenticationManager(BuggyWuggyUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** Applied on login: new session id (prevents session fixation) and a fresh CSRF token. */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }
}
