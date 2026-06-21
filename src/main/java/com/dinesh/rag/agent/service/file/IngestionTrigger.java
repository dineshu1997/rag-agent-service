package com.dinesh.rag.agent.service.file;

import java.util.UUID;

/**
 * Strategy for kicking off the asynchronous ingestion worker for a freshly
 * uploaded file. Two implementations:
 *
 * <ul>
 *   <li>{@code AsyncIngestionTrigger} (prod / default) — defers the call
 *       until the upload transaction has fully committed to Postgres, so
 *       the worker is guaranteed to see the new row.</li>
 *   <li>{@code ImmediateIngestionTrigger} (test profile) — calls the worker
 *       synchronously so Mockito {@code verify(...)} assertions can run
 *       even though the surrounding test transaction will roll back.</li>
 * </ul>
 *
 * <p>Replaces the previous {@code Class.forName("junit")} hack inside
 * {@link FileService}: production code no longer needs to know whether
 * tests are running — Spring profiles decide.</p>
 */
public interface IngestionTrigger {

    /**
     * Schedule ingestion for the given file id. Implementations may run
     * the call synchronously or asynchronously; callers must not assume
     * either.
     */
    void trigger(UUID fileId);
}
