/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Challenges and license codes. A license code is signed by the BuggyWuggy project with the
 * private key that belongs to {@link #PUBLIC_KEY}: "BWL1-" and, in base64url, the last valid day
 * (epoch day, 4 bytes) and the Ed25519 signature of "BuggyWuggy license v1|CHALLENGE|VALID-UNTIL".
 */
@Component
public class LicenseCode {

    /**
     * The public key of the license codes (X.509, base64): codes signed with another key are
     * refused.
     */
    static final String PUBLIC_KEY = "MCowBQYDK2VwAyEA7Qq8r4iVOGYM/UnLfb81CgnUHLxSoErriXnB50vqK2k=";

    static final String PREFIX = "BWL1-";

    /** Crockford's base32: no I, L, O or U, so a challenge can be read and typed. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final PublicKey key;
    private final SecureRandom random = new SecureRandom();

    public LicenseCode() {
        this(PUBLIC_KEY);
    }

    LicenseCode(String publicKey) {
        try {
            this.key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid license public key", e);
        }
    }

    /** A new challenge such as BW-7K2D-QX9M-4RTA-P0ZC (80 random bits). */
    public String newChallenge() {
        StringBuilder challenge = new StringBuilder("BW");
        for (int i = 0; i < 16; i++) {
            if (i % 4 == 0) {
                challenge.append('-');
            }
            challenge.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return challenge.toString();
    }

    static byte[] message(String challenge, LocalDate until) {
        return ("BuggyWuggy license v1|" + challenge.toUpperCase(Locale.ROOT) + "|" + until)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The last valid day of the code, when it answers the challenge. */
    public Optional<LocalDate> verify(String challenge, String code) {
        String value = code == null ? "" : code.replaceAll("\\s", "");
        if (!value.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value.substring(PREFIX.length()));
            if (bytes.length != 4 + 64) {
                return Optional.empty();
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            LocalDate until = LocalDate.ofEpochDay(buffer.getInt());
            byte[] signature = new byte[64];
            buffer.get(signature);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message(challenge, until));
            return verifier.verify(signature) ? Optional.of(until) : Optional.empty();
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return Optional.empty();
        }
    }
}
