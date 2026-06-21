package com.dinesh.rag.agent.service.file;

import com.dinesh.rag.agent.config.AppProperties;
import com.dinesh.rag.agent.domain.file.FileEntity;
import com.dinesh.rag.agent.domain.file.FileRepository;
import com.dinesh.rag.agent.domain.file.FileStatus;
import com.dinesh.rag.agent.domain.file.FileType;
import com.dinesh.rag.agent.domain.user.User;
import com.dinesh.rag.agent.exception.DuplicateResourceException;
import com.dinesh.rag.agent.exception.NotFoundException;
import com.dinesh.rag.agent.exception.UnsupportedFileTypeException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the user-facing file lifecycle: validate, persist blob,
 * dedupe by hash, insert PENDING row, kick off async ingestion, list,
 * read, delete.
 */
@Service
public class FileService {

    private final FileRepository fileRepository;
    private final FileStorageService storageService;
    private final FileIngestionService ingestionService;
    private final IngestionTrigger ingestionTrigger;
    private final List<String> allowedExtensions;

    public FileService(FileRepository fileRepository,
                       FileStorageService storageService,
                       FileIngestionService ingestionService,
                       IngestionTrigger ingestionTrigger,
                       AppProperties props) {
        this.fileRepository = fileRepository;
        this.storageService = storageService;
        this.ingestionService = ingestionService;
        this.ingestionTrigger = ingestionTrigger;
        this.allowedExtensions = props.files().allowedExtensions().stream()
                .map(String::toLowerCase)
                .toList();
    }

    // ----- write paths -------------------------------------------------

    @Transactional
    public FileEntity upload(MultipartFile upload, User currentUser) throws IOException {
        if (upload == null || upload.isEmpty()) {
            throw new IllegalArgumentException("File is empty.");
        }

        String originalName = upload.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("File must have a name.");
        }

        if (!isAllowedExtension(originalName)) {
            throw new UnsupportedFileTypeException(
                    "Unsupported file type. Allowed: " + String.join(", ", allowedExtensions));
        }

        UUID fileId = UUID.randomUUID();
        FileStorageService.StoredBlob blob = storageService.store(fileId, upload);

        // Reject duplicates by SHA-256 — saves embedding cost and prevents
        // double-counting of identical content in retrieval.
        fileRepository.findBySha256Hash(blob.sha256Hash()).ifPresent(existing -> {
            storageService.delete(blob.storagePath());
            throw new DuplicateResourceException(
                    "An identical file already exists: '" + existing.getDisplayName()
                            + "' (id=" + existing.getId() + ").");
        });

        FileEntity entity = FileEntity.builder()
                .id(fileId)
                .originalFilename(originalName)
                .displayName(originalName)
                .fileType(FileType.fromFilename(originalName))
                .sizeBytes(blob.sizeBytes())
                .sha256Hash(blob.sha256Hash())
                .storagePath(blob.storagePath())
                .createdBy(currentUser)
                .status(FileStatus.PENDING)
                .chunkCount(0)
                .build();

        FileEntity saved = fileRepository.save(entity);

        // Profile-aware trigger: AsyncIngestionTrigger in prod (afterCommit hook),
        // ImmediateIngestionTrigger in tests (so Mockito.verify can observe the call).
        ingestionTrigger.trigger(saved.getId());

        return saved;
    }

    @Transactional
    public void delete(UUID fileId, User currentUser) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> NotFoundException.of("File", fileId));

        if (!file.getCreatedBy().getId().equals(currentUser.getId())) {
            throw new AccessDeniedException("Only the uploader can delete this file.");
        }

        // Order matters: vectors first (cheap to lose if DB rollbacks), then DB row, then blob.
        ingestionService.deleteVectorsForFile(file.getId());
        fileRepository.delete(file);
        storageService.delete(file.getStoragePath());
    }

    // ----- read paths --------------------------------------------------

    @Transactional(readOnly = true)
    public FileEntity get(UUID fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> NotFoundException.of("File", fileId));
    }

    @Transactional(readOnly = true)
    public Page<FileEntity> search(String query, Pageable pageable) {
        return fileRepository.search(query, pageable);
    }

    // ----- helpers -----------------------------------------------------

    private boolean isAllowedExtension(String filename) {
        String lower = filename.toLowerCase();
        return allowedExtensions.stream().anyMatch(lower::endsWith);
    }
}
