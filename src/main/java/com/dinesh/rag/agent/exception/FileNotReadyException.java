package com.dinesh.rag.agent.exception;

import com.dinesh.rag.agent.domain.file.FileStatus;

import java.util.UUID;

/**
 * Thrown when an operation needs a file in {@link FileStatus#READY} state but the
 * file is still {@code PENDING}/{@code PROCESSING} or has {@code FAILED}.
 * Maps to HTTP 409 in the global handler.
 */
public class FileNotReadyException extends RuntimeException {

    public FileNotReadyException(String message) {
        super(message);
    }

    public static FileNotReadyException of(UUID fileId, FileStatus status) {
        return new FileNotReadyException(
                "File %s is not ready for querying (current status: %s).".formatted(fileId, status));
    }
}
