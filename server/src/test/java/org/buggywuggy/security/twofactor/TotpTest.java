/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpTest {

    /** The SHA-1 key of the test vectors in RFC 6238, appendix B. */
    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @CsvSource({
            "59, 94287082",
            "1111111109, 07081804",
            "1111111111, 14050471",
            "1234567890, 89005924",
            "2000000000, 69279037",
            "20000000000, 65353130"})
    void matchesTheTestVectorsOfRfc6238(long time, String expected) {
        long step = Totp.step(Instant.ofEpochSecond(time));
        assertThat(Totp.code(RFC_KEY, step, 8, "HmacSHA1")).isEqualTo(expected);
        // The six digits apps show are the last six of the same number.
        assertThat(Totp.code(RFC_KEY, step)).isEqualTo(expected.substring(2));
    }

    @Test
    void acceptsTheStepsAroundNowOnlyOnce() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long step = Totp.step(now);

        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step), now, 0)).hasValue(step);
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step - 1), now, 0)).hasValue(step - 1);
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step + 1), now, 0)).hasValue(step + 1);
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step - 2), now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step + 2), now, 0)).isEmpty();

        // Replay: a step at or before the last accepted one is refused.
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step), now, step)).isEmpty();
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step - 1), now, step - 1)).isEmpty();
        assertThat(Totp.verify(RFC_KEY, Totp.code(RFC_KEY, step + 1), now, step)).hasValue(step + 1);
    }

    @Test
    void refusesWhatIsNotSixDigits() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        String code = Totp.code(RFC_KEY, Totp.step(now));
        assertThat(Totp.verify(RFC_KEY, null, now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_KEY, "", now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_KEY, code + "0", now, 0)).isEmpty();
        assertThat(Totp.verify(RFC_KEY, "abcdef", now, 0)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "'', ''", "f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI"})
    void base32MatchesRfc4648(String plain, String encoded) {
        assertThat(Base32.encode(plain.getBytes(StandardCharsets.US_ASCII))).isEqualTo(encoded);
        assertThat(Base32.decode(encoded)).isEqualTo(plain.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void base32IgnoresCaseSpacesAndPadding() {
        assertThat(Base32.decode("mzxw 6ytb-oi======")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void qrCodeIsAnSvg() {
        String svg = QrCode.svg("otpauth://totp/BuggyWuggy:alice?secret=JBSWY3DPEHPK3PXP&issuer=BuggyWuggy");
        assertThat(svg).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .contains("<path fill=\"#000\" d=\"M")
                .endsWith("</svg>");
    }
}
