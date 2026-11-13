/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.LocalDate;
import java.util.Base64;

/** A key pair for tests, and license codes in the format that {@link LicenseCode} verifies. */
final class LicenseKeys {

    static final KeyPair PAIR;

    static {
        try {
            PAIR = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String PUBLIC = Base64.getEncoder().encodeToString(PAIR.getPublic().getEncoded());

    private LicenseKeys() {
    }

    static String sign(String challenge, LocalDate until) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(PAIR.getPrivate());
            signer.update(LicenseCode.message(challenge, until));
            byte[] signature = signer.sign();
            return LicenseCode.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(68).putInt((int) until.toEpochDay()).put(signature).array());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
