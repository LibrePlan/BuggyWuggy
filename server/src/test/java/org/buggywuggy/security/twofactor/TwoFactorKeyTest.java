/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TwoFactorKeyTest {

    @TempDir
    Path dataDir;

    @Test
    void createsAPrivateKeyFileOnFirstUseAndKeepsUsingIt() throws Exception {
        TwoFactorKey key = new TwoFactorKey(dataDir);
        assertThat(key.file()).doesNotExist();

        byte[] encrypted = key.encrypt(new byte[] {1, 2, 3}, "user:1");
        assertThat(key.file()).hasSize(32);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(key.file()))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(key.file().getParent())))
                .isEqualTo("rwx------");
        try (var files = Files.list(key.file().getParent())) {
            assertThat(files).containsExactly(key.file());
        }

        // After a restart: the same key from the file.
        assertThat(new TwoFactorKey(dataDir).decrypt(encrypted, "user:1")).containsExactly(1, 2, 3);
    }

    @Test
    void encryptsWithAFreshNonceAndBindsTheContext() {
        TwoFactorKey key = new TwoFactorKey(dataDir);
        byte[] first = key.encrypt(new byte[] {1, 2, 3}, "user:1");
        assertThat(key.encrypt(new byte[] {1, 2, 3}, "user:1")).isNotEqualTo(first);

        // A secret copied to another user's row does not decrypt.
        assertThatThrownBy(() -> key.decrypt(first, "user:2")).isInstanceOf(IllegalStateException.class);
        first[first.length - 1] ^= 1;
        assertThatThrownBy(() -> key.decrypt(first, "user:1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAKeyFileOfTheWrongSize() throws Exception {
        Files.createDirectories(dataDir.resolve("secrets"));
        Files.write(dataDir.resolve("secrets").resolve("two-factor.key"), new byte[16]);
        assertThatThrownBy(() -> new TwoFactorKey(dataDir).encrypt(new byte[1], "x"))
                .hasMessageContaining("32 bytes");
    }
}
