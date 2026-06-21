package com.dinesh.rag.agent.domain.file;

/**
 * Lifecycle of an uploaded file as it moves through the ingestion pipeline.
 *
 * <pre>
 *   PENDING    → row created, blob saved, not yet picked up by the worker
 *   PROCESSING → worker is parsing / chunking / embedding
 *   READY      → chunks are in the vector store, file is queryable
 *   FAILED     → ingestion blew up; see {@code error_message}
 * </pre>
 */
public enum FileStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED
}
