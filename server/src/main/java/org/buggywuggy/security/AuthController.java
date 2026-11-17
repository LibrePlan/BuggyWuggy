/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.security.sso.SsoProperties;
import org.buggywuggy.security.sso.SsoProperties.PasswordLogin;
import org.buggywuggy.security.twofactor.TwoFactorService;
import org.buggywuggy.security.twofactor.TwoFactorService.Verification;
import org.buggywuggy.security.twofactor.TwoFactorSettings;
import org.buggywuggy.user.UserDtos.MyselfResponse;
import org.buggywuggy.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
 *
 * <p>With two-factor login enabled ({@code buggywuggy.security.two-factor.enabled}), a correct
 * password does not log in yet: the session remembers the half-finished login for a few
 * minutes, and the user completes it with a code from the authenticator app (or a recovery
 * code) at {@code /two-factor}, or, without a second factor yet, sets one up at
 * {@code /two-factor/enrollment}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** How long a correct password waits for the second factor. */
    static final Duration PENDING_TIMEOUT = Duration.ofMinutes(5);
    private static final String PENDING = AuthController.class.getName() + ".PENDING_LOGIN";

    public record LoginRequest(@NotBlank @Size(max = 100) String username, @NotBlank @Size(max = 256) String password) {
    }

    public record CsrfResponse(String headerName, String token) {
    }

    /** What the login still needs: nothing, a code, or setting up the second factor first. */
    public enum LoginStep { DONE, TWO_FACTOR, TWO_FACTOR_ENROLLMENT }

    /** {@code user} is set when {@code step} is DONE. */
    public record LoginResponse(LoginStep step, @ApiNullable MyselfResponse user) {
    }

    /** A 6-digit code from the authenticator app, or a recovery code. */
    public record TwoFactorCodeRequest(@NotBlank @Size(max = 32) String code) {
    }

    public record TwoFactorLoginResponse(MyselfResponse user, boolean recoveryCodeUsed, int recoveryCodesLeft) {
    }

    /** The new secret: as {@code otpauth://} link, as QR code of that link (SVG), and for typing in. */
    public record EnrollmentResponse(String secret, String uri, String qrCodeSvg) {
    }

    /** The recovery codes are shown this once; only their hashes are kept. */
    public record EnrollmentResult(List<String> recoveryCodes, MyselfResponse user) {
    }

    /** A correct password waiting for the second factor; kept in the session. */
    record PendingLogin(long userId, String username, String throttleKey, Instant expiresAt, boolean enroll,
            byte[] pendingSecret) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        PendingLogin withSecret(byte[] secret) {
            return new PendingLogin(userId, username, throttleKey, expiresAt, enroll, secret);
        }
    }

    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final LoginThrottle throttle;
    private final UserService userService;
    private final BuggyWuggyUserDetailsService userDetailsService;
    private final TwoFactorSettings twoFactorSettings;
    private final TwoFactorService twoFactor;
    private final Clock clock;
    private final SsoProperties sso;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AuthenticationManager authenticationManager,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            SecurityContextRepository securityContextRepository, LoginThrottle throttle, UserService userService,
            BuggyWuggyUserDetailsService userDetailsService, TwoFactorSettings twoFactorSettings,
            TwoFactorService twoFactor, Clock clock, SsoProperties sso) {
        this.sso = sso;
        this.authenticationManager = authenticationManager;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.throttle = throttle;
        this.userService = userService;
        this.userDetailsService = userDetailsService;
        this.twoFactorSettings = twoFactorSettings;
        this.twoFactor = twoFactor;
        this.clock = clock;
    }

    /** Returns the CSRF token (and sets the XSRF-TOKEN cookie) for clients that need it up front. */
    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (sso.passwordLogin() == PasswordLogin.NONE) {
            throw ApiException.forbidden("auth.password-login-disabled");
        }
        // With password login for system administrators only, a wrong password and a correct
        // one of another user get the same answer, so it does not tell which passwords are right.
        String badCredentials = sso.passwordLogin() == PasswordLogin.SYSTEM_ADMINS
                ? "auth.bad-credentials-admins-only"
                : "auth.bad-credentials";
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
            throw new ApiException(HttpStatus.UNAUTHORIZED, badCredentials);
        }
        BuggyWuggyPrincipal principal = (BuggyWuggyPrincipal) authentication.getPrincipal();
        if (sso.passwordLogin() == PasswordLogin.SYSTEM_ADMINS && !principal.isSystemAdmin()) {
            throttle.recordFailure(throttleKey);
            throw new ApiException(HttpStatus.UNAUTHORIZED, badCredentials);
        }

        if (!twoFactorSettings.enabled()) {
            throttle.recordSuccess(throttleKey);
            return new LoginResponse(LoginStep.DONE, logIn(authentication, httpRequest, httpResponse));
        }

        // The password is right, but the login waits for the second factor. Failed codes count
        // as failed logins, so the throttle is reset only when the whole login succeeds.
        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);
        SecurityContext empty = contextHolder.createEmptyContext();
        contextHolder.setContext(empty);
        securityContextRepository.saveContext(empty, httpRequest, httpResponse);
        boolean enrolled = twoFactor.isEnrolled(principal.id());
        httpRequest.getSession().setAttribute(PENDING, new PendingLogin(principal.id(), principal.getUsername(),
                throttleKey, clock.instant().plus(PENDING_TIMEOUT), !enrolled, null));
        return new LoginResponse(enrolled ? LoginStep.TWO_FACTOR : LoginStep.TWO_FACTOR_ENROLLMENT, null);
    }

    /** Completes a login with a code from the authenticator app or a recovery code. */
    @PostMapping("/two-factor")
    public TwoFactorLoginResponse twoFactor(@Valid @RequestBody TwoFactorCodeRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        PendingLogin pending = pending(httpRequest, false);
        Verification verification = twoFactor.verify(pending.userId(), request.code());
        if (verification == Verification.FAILED) {
            failedCode(pending, httpRequest);
        }
        MyselfResponse user = complete(pending, httpRequest, httpResponse);
        return new TwoFactorLoginResponse(user, verification == Verification.RECOVERY_CODE,
                twoFactor.recoveryCodesLeft(pending.userId()));
    }

    /** Starts setting up the second factor: a new secret, the same one until the login ends. */
    @GetMapping("/two-factor/enrollment")
    public EnrollmentResponse enrollment(HttpServletRequest httpRequest) {
        PendingLogin pending = pending(httpRequest, true);
        if (pending.pendingSecret() == null) {
            pending = pending.withSecret(twoFactor.newPendingSecret(pending.userId()));
            httpRequest.getSession().setAttribute(PENDING, pending);
        }
        var enrollment = twoFactor.enrollment(pending.userId(), pending.username(), pending.pendingSecret());
        return new EnrollmentResponse(enrollment.secret(), enrollment.uri(), enrollment.qrCodeSvg());
    }

    /** Stores the second factor once a code from the app matches, and completes the login. */
    @PostMapping("/two-factor/enrollment")
    public EnrollmentResult enroll(@Valid @RequestBody TwoFactorCodeRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        PendingLogin pending = pending(httpRequest, true);
        if (pending.pendingSecret() == null) {
            throw ApiException.badRequest("auth.two-factor.no-enrollment");
        }
        if (twoFactor.isEnrolled(pending.userId())) {
            // Set up in another browser meanwhile.
            httpRequest.getSession().removeAttribute(PENDING);
            throw ApiException.conflict("auth.two-factor.already-enrolled");
        }
        var codes = twoFactor.enroll(pending.userId(), pending.pendingSecret(), request.code());
        if (codes.isEmpty()) {
            failedCode(pending, httpRequest);
        }
        return new EnrollmentResult(codes.orElseThrow(), complete(pending, httpRequest, httpResponse));
    }

    // --- helpers -----------------------------------------------------------------------

    /** The half-finished login of this session; fails when there is none (any more). */
    private PendingLogin pending(HttpServletRequest httpRequest, boolean enroll) {
        HttpSession session = httpRequest.getSession(false);
        if (session == null || !(session.getAttribute(PENDING) instanceof PendingLogin pending)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "auth.two-factor.no-login");
        }
        if (clock.instant().isAfter(pending.expiresAt())) {
            session.removeAttribute(PENDING);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "auth.two-factor.expired");
        }
        if (throttle.isBlocked(pending.throttleKey())) {
            session.removeAttribute(PENDING);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "auth.throttled");
        }
        if (pending.enroll() != enroll) {
            throw ApiException.badRequest(enroll ? "auth.two-factor.enrolled" : "auth.two-factor.not-enrolled");
        }
        return pending;
    }

    private void failedCode(PendingLogin pending, HttpServletRequest httpRequest) {
        throttle.recordFailure(pending.throttleKey());
        if (throttle.isBlocked(pending.throttleKey())) {
            // Too many wrong codes: start again with the password, once the throttle allows it.
            httpRequest.getSession().removeAttribute(PENDING);
            log.warn("Too many wrong two-factor codes for user {}", pending.username());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "auth.throttled");
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "auth.two-factor.bad-code");
    }

    /** Logs in the user of a pending login that passed the second factor. */
    private MyselfResponse complete(PendingLogin pending, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        httpRequest.getSession().removeAttribute(PENDING);
        // Loaded again: the account may have changed in the minutes since the password.
        BuggyWuggyPrincipal principal;
        try {
            principal = userDetailsService.loadUserByUsername(pending.username());
        } catch (AuthenticationException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "auth.bad-credentials");
        }
        if (!principal.isEnabled() || principal.id() != pending.userId()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "auth.bad-credentials");
        }
        principal.eraseCredentials();
        List<GrantedAuthority> authorities = new ArrayList<>(principal.getAuthorities());
        authorities.add(new SimpleGrantedAuthority(Authorities.TWO_FACTOR_VERIFIED));
        throttle.recordSuccess(pending.throttleKey());
        return logIn(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities),
                httpRequest, httpResponse);
    }

    private MyselfResponse logIn(Authentication authentication, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
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
