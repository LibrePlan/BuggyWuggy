/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.attachment;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * Stores file contents outside the database, addressed by a generated storage key. The v1
 * implementation uses the local file system; an S3-compatible store can implement the same
 * interface later.
 */
public interface BlobStore {

    /** The result of storing a file. */
    record StoredBlob(String key, long size, String sha256) {
    }

    /** Stores the stream's contents under a new key. Nothing is left behind if this fails. */
    StoredBlob put(InputStream content) throws IOException;

    /** The contents of a stored blob; {@code exists()} is false when it is missing. */
    Resource get(String key);

    /** Deletes a blob; a missing blob is not an error. */
    void delete(String key);
}
