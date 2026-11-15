/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second factor of the users: TOTP secrets (encrypted with {@link TwoFactorKey}) and
 * one-time recovery codes (stored as SHA-256 hashes).
 */
@Service
public class TwoFactorService {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorService.class);

    public static final int RECOVERY_CODES = 10;
    /** Base32 characters per recovery code: 50 random bits, shown as XXXXX-XXXXX. */
    static final int RECOVERY_CODE_LENGTH = 10;

    /** What a code that was entered at login turned out to be. */
    public enum Verification { TOTP, RECOVERY_CODE, FAILED }

    /** What the enrollment wizard shows: the secret as QR code, as link and for typing in. */
    public record Enrollment(String secret, String uri, String qrCodeSvg) {
    }

    private final JdbcTemplate jdbc;
    private final TwoFactorKey key;
    private final TwoFactorSettings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TwoFactorService(JdbcTemplate jdbc, TwoFactorKey key, TwoFactorSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.key = key;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isEnrolled(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from user_two_factor where user_id = ?)", Boolean.class, userId));
    }

    // --- enrollment ------------------------------------------------------------------

    /** A new secret, encrypted for keeping it in the session until the user confirms it. */
    public byte[] newPendingSecret(long userId) {
        byte[] secret = new byte[Totp.SECRET_BYTES];
        random.nextBytes(secret);
        return key.encrypt(secret, pendingContext(userId));
    }

    public Enrollment enrollment(long userId, String username, byte[] pendingSecret) {
        String secret = Base32.encode(key.decrypt(pendingSecret, pendingContext(userId)));
        String issuer = settings.issuer();
        // otpauth://totp/Issuer:user?secret=...&issuer=Issuer (Key Uri Format of Google Authenticator)
        String uri = "otpauth://totp/" + uriEncode(issuer) + ":" + uriEncode(username)
                + "?secret=" + secret + "&issuer=" + uriEncode(issuer)
                + "&algorithm=SHA1&digits=" + Totp.DIGITS + "&period=" + Totp.STEP_SECONDS;
        return new Enrollment(secret, uri, QrCode.svg(uri));
    }

    /**
     * Stores the secret once the user has shown, with a valid code, that the authenticator app
     * has it. Returns the new recovery codes, or nothing when the code is wrong.
     */
    @Transactional
    public Optional<List<String>> enroll(long userId, byte[] pendingSecret, String code) {
        byte[] secret = key.decrypt(pendingSecret, pendingContext(userId));
        OptionalLong step = Totp.verify(secret, normalizeTotp(code), clock.instant(), Long.MIN_VALUE);
        if (step.isEmpty()) {
            return Optional.empty();
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into user_two_factor (user_id, secret, enrolled_at, last_step) values (?, ?, ?, ?)
                on conflict (user_id) do update
                set secret = excluded.secret, enrolled_at = excluded.enrolled_at, last_step = excluded.last_step""",
                userId, key.encrypt(secret, storedContext(userId)), now, step.getAsLong());
        List<String> codes = newRecoveryCodes(userId);
        log.info("Two-factor login set up for user {}", userId);
        return Optional.of(codes);
    }

    private List<String> newRecoveryCodes(long userId) {
        jdbc.update("delete from user_recovery_code where user_id = ?", userId);
        List<String> codes = new ArrayList<>();
        while (codes.size() < RECOVERY_CODES) {
            byte[] bytes = new byte[(RECOVERY_CODE_LENGTH * 5 + 7) / 8];
            random.nextBytes(bytes);
            String code = Base32.encode(bytes).substring(0, RECOVERY_CODE_LENGTH);
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        jdbc.batchUpdate("insert into user_recovery_code (user_id, code_hash) values (?, ?)",
                codes.stream().map(c -> new Object[] {userId, sha256(c)}).toList());
        int half = RECOVERY_CODE_LENGTH / 2;
        return codes.stream().map(c -> c.substring(0, half) + "-" + c.substring(half)).toList();
    }

    // --- login -----------------------------------------------------------------------

    /** Checks a code from the authenticator app or, failing that, an unused recovery code. */
    @Transactional
    public Verification verify(long userId, String code) {
        String totp = normalizeTotp(code);
        if (totp.matches("\\d+")) {
            return verifyTotp(userId, totp) ? Verification.TOTP : Verification.FAILED;
        }
        String recovery = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (recovery.length() != RECOVERY_CODE_LENGTH) {
            return Verification.FAILED;
        }
        int used = jdbc.update("""
                update user_recovery_code set used_at = ?
                where user_id = ? and code_hash = ? and used_at is null""",
                Timestamp.from(clock.instant()), userId, sha256(recovery));
        if (used == 0) {
            return Verification.FAILED;
        }
        log.info("User {} logged in with a recovery code; {} left", userId, recoveryCodesLeft(userId));
        return Verification.RECOVERY_CODE;
    }

    private boolean verifyTotp(long userId, String code) {
        // "for update": two logins with the same code at the same moment cannot both succeed.
        List<Stored> rows = jdbc.query("select secret, last_step from user_two_factor where user_id = ? for update",
                (rs, n) -> new Stored(rs.getBytes(1), rs.getLong(2)), userId);
        if (rows.isEmpty()) {
            return false;
        }
        Stored stored = rows.getFirst();
        byte[] secret = key.decrypt(stored.secret(), storedContext(userId));
        OptionalLong step = Totp.verify(secret, code, clock.instant(), stored.lastStep());
        step.ifPresent(s -> jdbc.update("update user_two_factor set last_step = ? where user_id = ?", s, userId));
        return step.isPresent();
    }

    private record Stored(byte[] secret, long lastStep) {
    }

    @Transactional(readOnly = true)
    public int recoveryCodesLeft(long userId) {
        Integer left = jdbc.queryForObject(
                "select count(*) from user_recovery_code where user_id = ? and used_at is null", Integer.class, userId);
        return left == null ? 0 : left;
    }

    // --- administration ----------------------------------------------------------------

    /** Forgets the user's second factor: the next login starts the enrollment wizard. */
    @Transactional
    public boolean reset(long userId) {
        jdbc.update("delete from user_recovery_code where user_id = ?", userId);
        return jdbc.update("delete from user_two_factor where user_id = ?", userId) > 0;
    }

    // --- helpers -----------------------------------------------------------------------

    /** Apps show codes as "123 456"; accept them with spaces too. */
    private static String normalizeTotp(String code) {
        return code == null ? "" : code.replaceAll("\\s", "");
    }

    /** Bound into the encryption, so a secret only decrypts for its own user and purpose. */
    private static String storedContext(long userId) {
        return "buggywuggy-totp:" + userId;
    }

    private static String pendingContext(long userId) {
        return "buggywuggy-totp-pending:" + userId;
    }

    private static String uriEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
