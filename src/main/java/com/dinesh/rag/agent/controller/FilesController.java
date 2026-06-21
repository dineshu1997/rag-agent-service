package com.dinesh.rag.agent.controller;

import com.dinesh.rag.agent.domain.file.FileEntity;
import com.dinesh.rag.agent.dto.common.PageResponse;
import com.dinesh.rag.agent.dto.file.FileResponse;
import com.dinesh.rag.agent.dto.file.FileStatusResponse;
import com.dinesh.rag.agent.dto.file.FileUploadResponse;
import com.dinesh.rag.agent.service.auth.CurrentUserService;
import com.dinesh.rag.agent.service.file.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "Files", description = "Upload, browse, and delete documents in the shared RAG corpus.")
public class FilesController {

    private final FileService fileService;
    private final CurrentUserService currentUserService;

    public FilesController(FileService fileService, CurrentUserService currentUserService) {
        this.fileService = fileService;
        this.currentUserService = currentUserService;
    }

    @Operation(
            summary = "Upload a document for ingestion",
            description = "Saves the file, dedupes by SHA-256, and kicks off async parsing + "
                    + "embedding. Returns 202 with the new file's id and status=PENDING. Poll "
                    + "GET /api/v1/files/{id}/status to watch the file move through "
                    + "PENDING → PROCESSING → READY (or FAILED)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Upload accepted; ingestion queued",
                    content = @Content(schema = @Schema(implementation = FileUploadResponse.class))),
            @ApiResponse(responseCode = "400", description = "Empty file or missing filename", content = @Content),
            @ApiResponse(responseCode = "409", description = "Identical file already uploaded", content = @Content),
            @ApiResponse(responseCode = "415", description = "Unsupported file type", content = @Content),
            @ApiResponse(responseCode = "413", description = "File too large", content = @Content)
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileUploadResponse> upload(
            @Parameter(description = "Document to ingest.", required = true,
                    content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
            @RequestParam("file") MultipartFile file) throws IOException {

        FileEntity saved = fileService.upload(file, currentUserService.requireUser());
        FileUploadResponse body = new FileUploadResponse(
                saved.getId(), saved.getDisplayName(), saved.getStatus());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @Operation(
            summary = "List / search files",
            description = "Returns a paginated list of files in the shared corpus. "
                    + "Pass {@code q} to filter by display name (case-insensitive substring). "
                    + "Designed for the file-picker dropdown."
    )
    @GetMapping
    public PageResponse<FileResponse> list(
            @Parameter(description = "Optional case-insensitive search over display name")
            @RequestParam(value = "q", required = false) String q,
            @Parameter(description = "Zero-indexed page number") @RequestParam(value = "page", defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(value = "size", defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FileEntity> result = fileService.search(q, pageable);
        return PageResponse.from(result, FileResponse::from);
    }

    @Operation(summary = "Get a single file by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK"),
            @ApiResponse(responseCode = "404", description = "File not found", content = @Content)
    })
    @GetMapping("/{id}")
    public FileResponse get(@PathVariable("id") UUID id) {
        return FileResponse.from(fileService.get(id));
    }

    @Operation(
            summary = "Cheap status poll",
            description = "Returns just the status, chunk count and error (if any). "
                    + "Use this for polling during ingestion rather than the full GET."
    )
    @GetMapping("/{id}/status")
    public FileStatusResponse status(@PathVariable("id") UUID id) {
        FileEntity f = fileService.get(id);
        return new FileStatusResponse(f.getId(), f.getStatus(), f.getChunkCount(), f.getErrorMessage());
    }

    @Operation(
            summary = "Delete a file",
            description = "Removes vectors from Qdrant, deletes the DB row, and removes the blob. "
                    + "Only the uploader (created_by) may delete a file."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "403", description = "Caller is not the uploader", content = @Content),
            @ApiResponse(responseCode = "404", description = "File not found", content = @Content)
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        fileService.delete(id, currentUserService.requireUser());
        return ResponseEntity.noContent().build();
    }
}
