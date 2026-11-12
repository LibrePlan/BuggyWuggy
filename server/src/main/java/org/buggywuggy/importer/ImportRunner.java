/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.importer.ImportJobs.Level;
import org.buggywuggy.importer.ImportJobs.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Runs import jobs one at a time in the background.
 *
 * <p>Access tokens for the source are only kept in memory, here, never in the database or
 * the log: a failed or interrupted job can be retried without typing the token again, until
 * BuggyWuggy restarts or {@link #CREDENTIAL_LIFETIME} has passed.
 */
@Component
public class ImportRunner {

    static final Duration CREDENTIAL_LIFETIME = Duration.ofHours(24);

    /** The work of one job. Throwing fails the job; the message is shown to the administrator. */
    public interface Work {
        void run(Context context) throws Exception;
    }

    /** What a running job can use. */
    public final class Context implements ReadOnlyHttp.Waits {

        private final long jobId;
        private volatile String status;

        Context(long jobId) {
            this.jobId = jobId;
        }

        public long jobId() {
            return jobId;
        }

        public boolean cancelled() {
            return cancelled.contains(jobId);
        }

        public void info(String message) {
            jobs.log(jobId, Level.INFO, message);
        }

        public void warn(String message) {
            jobs.log(jobId, Level.WARN, message);
        }

        /** The one-line status the administration shows while the job runs. */
        public void status(String text) {
            status = text;
            jobs.status(jobId, text, null);
        }

        @Override
        public void waiting(String reason, Instant until) {
            String time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(until);
            info("Waiting until " + time + " for the " + reason + ".");
            jobs.status(jobId, "Waiting for the " + reason, until);
        }

        @Override
        public void resumed() {
            jobs.status(jobId, status, null);
        }

        /** A readable message for an error, in English like the rest of the log. */
        public String describe(Throwable e) {
            return ImportRunner.this.describe(e);
        }
    }

    /** Thrown inside a job when the administrator cancelled it. */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("cancelled", null, false, false);
        }
    }

    private record Credential(String token, Instant expires) {
    }

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);

    private final ImportJobs jobs;
    private final Clock clock;
    private final MessageSource messages;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "import");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Long> cancelled = ConcurrentHashMap.newKeySet();
    private final Map<Long, Credential> credentials = new ConcurrentHashMap<>();

    public ImportRunner(ImportJobs jobs, Clock clock, MessageSource messages) {
        this.jobs = jobs;
        this.clock = clock;
        this.messages = messages;
    }

    String describe(Throwable e) {
        if (e instanceof ApiException api) {
            return messages.getMessage(api.messageKey(), api.args(), api.messageKey(), Locale.ENGLISH);
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public void start(long jobId, String token, Work work) {
        if (token != null && !token.isBlank()) {
            credentials.put(jobId, new Credential(token, clock.instant().plus(CREDENTIAL_LIFETIME)));
        }
        executor.submit(() -> execute(jobId, work));
    }

    public void cancel(long jobId) {
        cancelled.add(jobId);
    }

    /** The token a job was started with, if it is still known. */
    public String token(long jobId) {
        Credential credential = credentials.get(jobId);
        if (credential == null) {
            return null;
        }
        if (credential.expires().isBefore(clock.instant())) {
            credentials.remove(jobId);
            return null;
        }
        return credential.token();
    }

    public boolean hasToken(long jobId) {
        return token(jobId) != null;
    }

    private void execute(long jobId, Work work) {
        try {
            work.run(new Context(jobId));
            jobs.finish(jobId, Status.SUCCEEDED, null);
        } catch (CancelledException e) {
            jobs.log(jobId, Level.WARN, "Cancelled by an administrator.");
            jobs.finish(jobId, Status.CANCELLED, "Cancelled by an administrator.");
        } catch (Exception | Error e) {
            log.warn("Import job {} failed", jobId, e);
            String message = describe(e);
            try {
                jobs.log(jobId, Level.ERROR, message);
                jobs.finish(jobId, Status.FAILED, message);
            } catch (RuntimeException inner) {
                log.error("Could not record the failure of import job {}", jobId, inner);
            }
        } finally {
            cancelled.remove(jobId);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
