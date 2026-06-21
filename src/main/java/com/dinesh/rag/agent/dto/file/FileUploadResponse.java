package com.dinesh.rag.agent.dto.file;

import com.dinesh.rag.agent.domain.file.FileStatus;

import java.util.UUID;

/** Returned from POST /api/v1/files — gives the client a handle to poll. */
public record FileUploadResponse(
        UUID id,
        String displayName,
        FileStatus status
) {}
