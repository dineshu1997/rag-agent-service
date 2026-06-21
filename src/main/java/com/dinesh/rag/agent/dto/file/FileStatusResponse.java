package com.dinesh.rag.agent.dto.file;

import com.dinesh.rag.agent.domain.file.FileStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/** Cheap polling endpoint payload. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileStatusResponse(
        UUID id,
        FileStatus status,
        int chunkCount,
        String errorMessage
) {}
