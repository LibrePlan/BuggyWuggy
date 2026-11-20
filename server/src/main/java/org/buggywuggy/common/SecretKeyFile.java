/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

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

/**
 * A key file of 32 random bytes in {@code data-dir/secrets/}, created on first use and readable
 * only for BuggyWuggy, that encrypts secrets in the database with AES-256-GCM: a copy of the
 * database alone (e.g. a backup) does not give them away. Each kind of secret has a file of its
 * own. Without the file, the secrets it encrypted cannot be read any more.
 */
public class SecretKeyFile {

    private static final Logger log = LoggerFactory.getLogger(SecretKeyFile.class);
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path file;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    private final String purpose;

    /** {@code name}: the file name without ".key"; {@code purpose}: for messages, e.g. "two-factor". */
    protected SecretKeyFile(Path dataDir, String name, String purpose) {
        this.file = dataDir.toAbsolutePath().normalize().resolve("secrets").resolve(name + ".key");
        this.purpose = purpose;
    }

    public Path file() {
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
            throw new IllegalStateException("Cannot decrypt a " + purpose + " secret: was " + file + " replaced?", e);
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
                ? Files.createTempFile(dir, "key", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile(dir, "key", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Arrays.fill(bytes, (byte) 0);
            Files.deleteIfExists(temp);
        }
        log.info("Created the key for {} secrets: {}. Keep it with the backups of the data directory.", purpose, file);
    }
}
