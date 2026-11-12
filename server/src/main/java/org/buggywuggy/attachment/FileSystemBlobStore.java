/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Stores blobs as files under {@code <data dir>/attachments/<xx>/<uuid>}, where {@code xx} are
 * the first two characters of the key, so no directory grows too large. Files are written to a
 * temporary name first and then moved into place, so a crash never leaves a half-written blob
 * under a valid key.
 */
@Component
public class FileSystemBlobStore implements BlobStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemBlobStore.class);
    private static final Pattern KEY = Pattern.compile("^[0-9a-f]{32}$");

    private final Path root;

    public FileSystemBlobStore(@Value("${buggywuggy.data-dir}") Path dataDir) throws IOException {
        this.root = dataDir.toAbsolutePath().normalize().resolve("attachments");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IOException("Cannot create the attachment directory " + root
                    + ". Set BUGGYWUGGY_DATA_DIR to a writable directory.", e);
        }
        if (!Files.isWritable(root)) {
            throw new IOException("The attachment directory " + root + " is not writable. "
                    + "Set BUGGYWUGGY_DATA_DIR to a writable directory.");
        }
        log.info("Attachments are stored in {}", root);
    }

    @Override
    public StoredBlob put(InputStream content) throws IOException {
        String key = UUID.randomUUID().toString().replace("-", "");
        Path target = path(key);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
        try {
            MessageDigest sha256 = sha256();
            long size;
            try (InputStream in = new DigestInputStream(content, sha256);
                    OutputStream out = Files.newOutputStream(temp)) {
                size = in.transferTo(out);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target);
            }
            return new StoredBlob(key, size, HexFormat.of().formatHex(sha256.digest()));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public Resource get(String key) {
        return new FileSystemResource(path(key));
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException e) {
            // The database no longer refers to it; a leftover file only costs disk space.
            log.warn("Could not delete attachment file {}: {}", key, e.toString());
        }
    }

    private Path path(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return root.resolve(key.substring(0, 2)).resolve(key);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
