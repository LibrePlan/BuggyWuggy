/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LicenseCodeTest {

    private final LicenseCode codes = new LicenseCode(LicenseKeys.PUBLIC);

    @Test
    void acceptsOnlyCodesSignedForTheChallenge() {
        String challenge = codes.newChallenge();
        assertThat(challenge).matches("BW(-[0-9A-HJKMNP-TV-Z]{4}){4}");
        LocalDate until = LocalDate.of(2027, 11, 13);
        String code = LicenseKeys.sign(challenge, until);

        assertThat(codes.verify(challenge, code)).contains(until);
        assertThat(codes.verify(challenge.toLowerCase(), " " + code + "\n")).contains(until);
        assertThat(codes.verify(codes.newChallenge(), code)).isEmpty();
        assertThat(codes.verify(challenge, code.substring(0, code.length() - 2) + "AA")).isEmpty();
        assertThat(codes.verify(challenge, "BWL1-nonsense")).isEmpty();
        assertThat(codes.verify(challenge, null)).isEmpty();
        // Signed with another key, such as the one in the source code.
        assertThat(new LicenseCode().verify(challenge, code)).isEmpty();
    }
}
