/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords of RFC 6238 with the settings every authenticator app
 * supports: HMAC-SHA1, 6 digits, a new code every 30 seconds.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    /** Secret length recommended by RFC 4226 for HMAC-SHA1. */
    public static final int SECRET_BYTES = 20;
    /** Steps before and after the current one that are accepted too, for clocks that differ a little. */
    static final int WINDOW = 1;

    private Totp() {
    }

    public static long step(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), STEP_SECONDS);
    }

    /** The code for one time step, with leading zeros. */
    public static String code(byte[] secret, long step) {
        return code(secret, step, DIGITS, "HmacSHA1");
    }

    static String code(byte[] secret, long step, int digits, String algorithm) {
        byte[] hash;
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret, algorithm));
            hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        // Dynamic truncation, RFC 4226 section 5.3.
        int offset = hash[hash.length - 1] & 0xf;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        int otp = binary % (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", otp);
    }

    /**
     * The time step whose code is {@code code}, within the window around {@code now} and after
     * {@code lastStep}, so a code that was used once is never accepted again.
     */
    public static OptionalLong verify(byte[] secret, String code, Instant now, long lastStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = step(now);
        long found = -1;
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            // Compare every step in constant time, so the timing does not tell which one matched.
            boolean match = MessageDigest.isEqual(code(secret, step).getBytes(), code.getBytes());
            if (match && step > lastStep && found < 0) {
                found = step;
            }
        }
        return found < 0 ? OptionalLong.empty() : OptionalLong.of(found);
    }
}
