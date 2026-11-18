/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import java.time.LocalDate;

/** Donation codes of the test key, for tests outside this package. */
public final class TestDonorCodes {

    private TestDonorCodes() {
    }

    /** Verifies codes of the test key instead of the real one. */
    public static LicenseCode verifier() {
        return new LicenseCode(LicenseKeys.PUBLIC);
    }

    public static String sign(String challenge, LocalDate until) {
        return LicenseKeys.sign(challenge, until);
    }
}
