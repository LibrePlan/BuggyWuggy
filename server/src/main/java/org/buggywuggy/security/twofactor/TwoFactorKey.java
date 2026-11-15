/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.security.twofactor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts the TOTP secrets in the database with AES-256-GCM, so that a copy of the database
 * alone (e.g. a backup) does not give away the second factor. The key is a file of 32 random
 * bytes, {@code data-dir/secrets/two-factor.key}, created on first use and readable only for
 * BuggyWuggy. Without it, every user has to set up two-factor login again.
 */
@Component
public class TwoFactorKey {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorKey.class);
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path file;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    public TwoFactorKey(@Value("${buggywuggy.data-dir}") Path dataDir) {
        this.file = dataDir.toAbsolutePath().normalize().resolve("secrets").resolve("two-factor.key");
    }

    Path file() {
        return file;
    }

    /** Nonce followed by ciphertext and tag. {@code context} must be the same when decrypting. */
    public byte[] encrypt(byte[] plain, String context) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] decrypt(byte[] encrypted, String context) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, encrypted, 0, NONCE_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(encrypted, NONCE_BYTES, encrypted.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot decrypt a two-factor secret: was " + file + " replaced?", e);
        }
    }

    private SecretKey key() {
        SecretKey k = key;
        if (k == null) {
            synchronized (this) {
                k = key;
                if (k == null) {
                    k = new SecretKeySpec(loadOrCreate(), "AES");
                    key = k;
                }
            }
        }
        return k;
    }

    private byte[] loadOrCreate() {
        try {
            if (!Files.exists(file)) {
                create();
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length != KEY_BYTES) {
                throw new IllegalStateException(file + " must hold exactly " + KEY_BYTES + " bytes");
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create " + file, e);
        }
    }

    private void create() throws IOException {
        Path dir = file.getParent();
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (!Files.isDirectory(dir)) {
            if (posix) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(dir);
            }
        }
        byte[] bytes = new byte[KEY_BYTES];
        random.nextBytes(bytes);
        // Written to a temporary file first, so a half-written key file never exists.
        Path temp = posix
                ? Files.createTempFile(dir, "two-factor", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(dir, "two-factor", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            Files.deleteIfExists(temp);
        }
        log.info("Created the key for two-factor secrets: {}. Keep it with the backups of the data directory.", file);
    }
}
