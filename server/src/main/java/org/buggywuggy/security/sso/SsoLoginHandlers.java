/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.sso;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.buggywuggy.security.Authorities;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.buggywuggy.security.BuggyWuggyUserDetailsService;
import org.buggywuggy.security.sso.SsoUserService.SsoUser;
import org.buggywuggy.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonWriter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The web side of single sign-on: starting a login ({@code /oauth2/authorization/<id>}), its
 * outcome (back to the web UI, or to the login page with an error code), and logging out at
 * the provider too.
 */
public final class SsoLoginHandlers {

    private static final Logger log = LoggerFactory.getLogger(SsoLoginHandlers.class);

    static final String AUTHORIZATION_PATH = "/oauth2/authorization/";
    private static final String NEXT = SsoLoginHandlers.class.getName() + ".NEXT";
    private static final String PROVIDER = SsoLoginHandlers.class.getName() + ".PROVIDER";
    private static final String ID_TOKEN = SsoLoginHandlers.class.getName() + ".ID_TOKEN";
    private static final String LOGOUT_URL = SsoLoginHandlers.class.getName() + ".LOGOUT_URL";

    private final SsoProperties properties;
    private final SsoClientRegistrations registrations;
    private final BuggyWuggyUserDetailsService userDetails;
    private final UserService userService;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public SsoLoginHandlers(SsoProperties properties, SsoClientRegistrations registrations,
            BuggyWuggyUserDetailsService userDetails, UserService userService,
            SecurityContextRepository securityContextRepository) {
        this.properties = properties;
        this.registrations = registrations;
        this.userDetails = userDetails;
        this.userService = userService;
        this.securityContextRepository = securityContextRepository;
    }

    /** Only redirects within BuggyWuggy, like the web UI's own {@code next}. */
    static String safeNext(String next) {
        return next != null && next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/\\") ? next : "/";
    }

    private static void toLoginPage(HttpServletRequest request, HttpServletResponse response, String code)
            throws IOException {
        response.sendRedirect(request.getContextPath() + "/login?sso-error="
                + URLEncoder.encode(code, StandardCharsets.UTF_8));
    }

    /**
     * Before Spring Security sends the browser to the provider: an unknown or unreachable
     * provider goes back to the login page, and {@code ?next=} is kept for after the login.
     */
    public OncePerRequestFilter startFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getRequestURI().startsWith(request.getContextPath() + AUTHORIZATION_PATH);
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                String id = request.getRequestURI().substring((request.getContextPath() + AUTHORIZATION_PATH).length());
                if (!properties.providers().containsKey(id)) {
                    toLoginPage(request, response, "sso.unknown-provider");
                    return;
                }
                if (registrations.findByRegistrationId(id) == null) {
                    toLoginPage(request, response, "sso.unavailable");
                    return;
                }
                request.getSession().setAttribute(NEXT, safeNext(request.getParameter("next")));
                chain.doFilter(request, response);
            }
        };
    }

    /** Logs in the BuggyWuggy account, like a password login, and returns to the web UI. */
    public AuthenticationSuccessHandler successHandler() {
        return (request, response, authentication) -> {
            SsoUser user = (SsoUser) authentication.getPrincipal();
            BuggyWuggyPrincipal principal;
            try {
                principal = userDetails.loadForSso(user.accountId());
            } catch (UsernameNotFoundException e) {
                logOut(request, response);
                toLoginPage(request, response, "sso.inactive");
                return;
            }
            List<GrantedAuthority> authorities = new ArrayList<>(principal.getAuthorities());
            authorities.add(new SimpleGrantedAuthority(Authorities.SSO_LOGIN));
            SecurityContext context = contextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
            contextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            HttpSession session = request.getSession();
            session.setAttribute(PROVIDER, user.provider());
            session.setAttribute(ID_TOKEN, user.getIdToken().getTokenValue());
            Object next = session.getAttribute(NEXT);
            session.removeAttribute(NEXT);
            userService.recordLogin(principal.id());
            log.info("{} logged in through single sign-on provider {}", principal.getUsername(), user.provider());
            response.sendRedirect(request.getContextPath() + safeNext(next instanceof String s ? s : null));
        };
    }

    private void logOut(HttpServletRequest request, HttpServletResponse response) {
        SecurityContext empty = contextHolder.createEmptyContext();
        contextHolder.setContext(empty);
        securityContextRepository.saveContext(empty, request, response);
    }

    /** Back to the login page with a code the web UI explains. */
    public AuthenticationFailureHandler failureHandler() {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException e) -> {
            String code = "sso.failed";
            if (e instanceof OAuth2AuthenticationException oauth) {
                String error = oauth.getError().getErrorCode();
                if (error.startsWith("sso.")) {
                    code = error;
                } else if ("access_denied".equals(error)) {
                    code = "sso.cancelled";
                } else {
                    log.warn("Single sign-on login failed: {} {}", error, oauth.getError().getDescription());
                }
            } else {
                log.warn("Single sign-on login failed: {}", e.getMessage());
            }
            toLoginPage(request, response, code);
        };
    }

    /**
     * Runs before the session ends: works out where the browser logs out at the provider
     * (OpenID Connect RP-initiated logout), when the provider supports it.
     */
    public LogoutHandler logoutHandler() {
        return (request, response, authentication) -> {
            HttpSession session = request.getSession(false);
            if (session == null || !(session.getAttribute(PROVIDER) instanceof String provider)
                    || !(session.getAttribute(ID_TOKEN) instanceof String idToken)) {
                return;
            }
            var settings = properties.providers().get(provider);
            ClientRegistration registration = registrations.findByRegistrationId(provider);
            if (settings == null || !settings.logoutAtProvider() || registration == null) {
                return;
            }
            Object endpoint = registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
            if (!(endpoint instanceof String endSession) || endSession.isBlank()) {
                return;
            }
            String afterLogout = ServletUriComponentsBuilder.fromContextPath(request).path("/login").toUriString();
            request.setAttribute(LOGOUT_URL, UriComponentsBuilder.fromUriString(endSession)
                    .queryParam("id_token_hint", idToken)
                    .queryParam("client_id", registration.getClientId())
                    .queryParam("post_logout_redirect_uri", afterLogout)
                    .encode().build().toUriString());
        };
    }

    /**
     * 204 after a plain logout; 200 with {@code {"redirectUrl": ...}} when the browser must
     * go on to log out at the identity provider.
     */
    public static LogoutSuccessHandler logoutSuccessHandler() {
        return (request, response, authentication) -> {
            if (!(request.getAttribute(LOGOUT_URL) instanceof String url)) {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return;
            }
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(JsonWriter.standard().writeToString(Map.of("redirectUrl", url)));
        };
    }
}
