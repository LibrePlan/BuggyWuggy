/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.attachment;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps attachment files in step with the database. Files are deleted only after the
 * transaction that removed their rows has committed; a rollback keeps them. Rows disappear
 * by cascade when an issue or project is deleted, so those paths collect the keys first.
 */
@Component
public class AttachmentFiles {

    private final BlobStore blobs;
    private final NamedParameterJdbcTemplate jdbc;

    public AttachmentFiles(BlobStore blobs, NamedParameterJdbcTemplate jdbc) {
        this.blobs = blobs;
        this.jdbc = jdbc;
    }

    public List<String> keysOfIssue(long issueId) {
        return jdbc.queryForList("select storage_key from attachment where issue_id = :id",
                Map.of("id", issueId), String.class);
    }

    /** Storage keys of an issue's attachments and those of its child issues. */
    public List<String> keysOfIssueAndChildren(long issueId) {
        return jdbc.queryForList("""
                select a.storage_key from attachment a join issue i on i.id = a.issue_id
                where i.id = :id or i.parent_id = :id""", Map.of("id", issueId), String.class);
    }

    public List<String> keysOfProject(long projectId) {
        return jdbc.queryForList("""
                select a.storage_key from attachment a join issue i on i.id = a.issue_id
                where i.project_id = :id""", Map.of("id", projectId), String.class);
    }

    /** Deletes the files once the current transaction commits (at once without a transaction). */
    public void deleteAfterCommit(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        List<String> copy = List.copyOf(keys);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            copy.forEach(blobs::delete);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                copy.forEach(blobs::delete);
            }
        });
    }

    /** Deletes the files if the current transaction rolls back, e.g. for a failed upload. */
    public void deleteOnRollback(Collection<String> keys) {
        List<String> copy = List.copyOf(keys);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    copy.forEach(blobs::delete);
                }
            }
        });
    }
}
