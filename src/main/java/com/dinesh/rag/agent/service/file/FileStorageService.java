package com.dinesh.rag.agent.service.file;

import com.dinesh.rag.agent.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Persists uploaded blobs to the local filesystem and hashes them for
 * duplicate detection in a single streaming pass (no rereading from disk).
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path storageRoot;

    public FileStorageService(AppProperties props) {
        this.storageRoot = Paths.get(props.files().storagePath()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureStorageRootExists() throws IOException {
        Files.createDirectories(storageRoot);
        log.info("File storage root: {}", storageRoot);
    }

    /**
     * Save the given multipart upload under {@code {storageRoot}/{fileId}{ext}}.
     * SHA-256 is computed as bytes stream through; we never read the file twice.
     */
    public StoredBlob store(UUID fileId, MultipartFile upload) throws IOException {
        String ext = extractExtension(upload.getOriginalFilename());
        Path target = storageRoot.resolve(fileId + ext);

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK standard algorithm — should never happen.
            throw new IllegalStateException("SHA-256 not available", e);
        }

        long bytesWritten;
        try (InputStream in = new DigestInputStream(upload.getInputStream(), digest);
             OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bytesWritten = in.transferTo(out);
        }

        String hash = HexFormat.of().formatHex(digest.digest());
        return new StoredBlob(target.toString(), hash, bytesWritten);
    }

    /** Best-effort delete of a previously-stored blob. */
    public void delete(String storagePath) {
        try {
            Files.deleteIfExists(Paths.get(storagePath));
        } catch (IOException e) {
            log.warn("Failed to delete blob {}: {}", storagePath, e.getMessage());
        }
    }

    private String extractExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return (dot < 0) ? "" : filename.substring(dot).toLowerCase();
    }

    public record StoredBlob(String storagePath, String sha256Hash, long sizeBytes) {}
}
