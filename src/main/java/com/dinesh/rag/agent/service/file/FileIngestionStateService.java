package com.dinesh.rag.agent.service.file;

import com.dinesh.rag.agent.domain.file.FileEntity;
import com.dinesh.rag.agent.domain.file.FileRepository;
import com.dinesh.rag.agent.domain.file.FileStatus;
import com.dinesh.rag.agent.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Small companion to {@link FileIngestionService} that owns the
 * per-step status transitions in their own short transactions. Lives
 * separately so the {@code @Async} orchestrator doesn't have to be
 * {@code @Transactional} (which doesn't compose cleanly with async).
 */
@Service
public class FileIngestionStateService {

    private final FileRepository fileRepository;

    public FileIngestionStateService(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    /** Read just the fields the worker needs, without touching LAZY relations. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public IngestSnapshot loadForIngest(UUID fileId) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> NotFoundException.of("File", fileId));
        return new IngestSnapshot(
                file.getId(),
                file.getDisplayName(),
                file.getStoragePath(),
                file.getFileType().name()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID fileId) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> NotFoundException.of("File", fileId));
        file.setStatus(FileStatus.PROCESSING);
        fileRepository.save(file);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markReady(UUID fileId, int chunkCount) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> NotFoundException.of("File", fileId));
        file.setStatus(FileStatus.READY);
        file.setChunkCount(chunkCount);
        file.setErrorMessage(null);
        fileRepository.save(file);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID fileId, String errorMessage) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> NotFoundException.of("File", fileId));
        file.setStatus(FileStatus.FAILED);
        // truncate so we don't blow up TEXT columns with monster stack traces
        file.setErrorMessage(errorMessage == null || errorMessage.length() < 4000
                ? errorMessage
                : errorMessage.substring(0, 4000));
        fileRepository.save(file);
    }

    public record IngestSnapshot(UUID id, String displayName, String storagePath, String fileType) {}
}
