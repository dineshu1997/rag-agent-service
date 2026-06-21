package com.dinesh.rag.agent.dto.file;

import com.dinesh.rag.agent.domain.file.FileEntity;
import com.dinesh.rag.agent.domain.file.FileStatus;
import com.dinesh.rag.agent.domain.file.FileType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Full representation of a file returned by GET /api/v1/files and
 * GET /api/v1/files/{id}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileResponse(
        UUID id,
        String displayName,
        String originalFilename,
        FileType fileType,
        long sizeBytes,
        FileStatus status,
        int chunkCount,
        UploaderSummary createdBy,
        Instant createdAt,
        Instant updatedAt,
        String errorMessage
) {

    public record UploaderSummary(UUID id, String email, String displayName) {}

    public static FileResponse from(FileEntity f) {
        var uploader = new UploaderSummary(
                f.getCreatedBy().getId(),
                f.getCreatedBy().getEmail(),
                f.getCreatedBy().getDisplayName()
        );
        return new FileResponse(
                f.getId(),
                f.getDisplayName(),
                f.getOriginalFilename(),
                f.getFileType(),
                f.getSizeBytes(),
                f.getStatus(),
                f.getChunkCount(),
                uploader,
                f.getCreatedAt(),
                f.getUpdatedAt(),
                f.getErrorMessage()
        );
    }
}
