package com.dinesh.rag.agent.service.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Async worker that turns an uploaded blob into vector-store chunks.
 *
 * <p>Pipeline per file:
 * <ol>
 *   <li>load row, mark PROCESSING</li>
 *   <li>Tika-parse the blob</li>
 *   <li>split with TokenTextSplitter</li>
 *   <li>attach RAG metadata ({@code file_id}, {@code file_name},
 *       {@code chunk_index}, {@code file_type}) so we can filter at query time</li>
 *   <li>upsert batches into Qdrant</li>
 *   <li>mark READY (or FAILED with the captured error)</li>
 * </ol>
 *
 * <p>Runs on the {@code ingestionExecutor} thread pool. NOT marked
 * {@code @Transactional} — state transitions are delegated to
 * {@link FileIngestionStateService} to get a fresh, short Tx per step.</p>
 */
@Service
public class FileIngestionService {

    private static final Logger log = LoggerFactory.getLogger(FileIngestionService.class);

    /** Batch size for vectorStore.add() calls — keeps Ollama embedding latency predictable. */
    private static final int EMBED_BATCH_SIZE = 16;

    private final VectorStore vectorStore;
    private final FileIngestionStateService stateService;

    public FileIngestionService(VectorStore vectorStore,
                                FileIngestionStateService stateService) {
        this.vectorStore = vectorStore;
        this.stateService = stateService;
    }

    @Async("ingestionExecutor")
    public void ingest(UUID fileId) {
        log.info("Ingest started: fileId={}", fileId);

        FileIngestionStateService.IngestSnapshot snap;
        try {
            snap = stateService.loadForIngest(fileId);
            stateService.markProcessing(fileId);
        } catch (Exception bootErr) {
            log.error("Could not start ingest for {}: {}", fileId, bootErr.getMessage(), bootErr);
            safelyMarkFailed(fileId, "Could not load file row: " + bootErr.getMessage());
            return;
        }

        try {
            int chunkCount = parseChunkAndEmbed(snap);
            stateService.markReady(fileId, chunkCount);
            log.info("Ingest finished: fileId={} chunks={}", fileId, chunkCount);

        } catch (Exception ex) {
            log.error("Ingest failed for {}: {}", fileId, ex.getMessage(), ex);
            safelyMarkFailed(fileId, ex.toString());
        }
    }

    /**
     * Remove all vectors associated with a file. Called by the DELETE endpoint
     * before we drop the row + blob.
     *
     * <p><strong>Fail loud.</strong> A previous version of this method
     * swallowed exceptions and let the row + blob be removed even if Qdrant
     * was unreachable, which produced "ghost citations" in chat answers when
     * the orphan vectors got retrieved later. Now we let the exception
     * propagate; the surrounding {@link FileService#delete} method runs
     * inside a transaction so the DB row stays intact and the client sees
     * a clean 500 they can retry.</p>
     */
    public void deleteVectorsForFile(UUID fileId) {
        Filter.Expression expr = new FilterExpressionBuilder()
                .eq("file_id", fileId.toString())
                .build();
        vectorStore.delete(expr);
    }

    // ----- internals ---------------------------------------------------

    private int parseChunkAndEmbed(FileIngestionStateService.IngestSnapshot snap) {
        // 1. Parse via Tika — handles PDF, DOCX, TXT, MD, HTML, etc.
        var resource = new FileSystemResource(snap.storagePath());
        var reader = new TikaDocumentReader(resource);
        List<Document> rawDocs = reader.get();

        if (rawDocs == null || rawDocs.isEmpty()) {
            throw new IllegalStateException("Tika produced no documents from " + snap.storagePath());
        }

        // 2. Chunk via TokenTextSplitter (defaults are reasonable for nomic-embed-text's 8K context).
        TokenTextSplitter splitter = TokenTextSplitter.builder().build();
        List<Document> chunks = splitter.apply(rawDocs);

        if (chunks.isEmpty()) {
            throw new IllegalStateException("Splitter produced no chunks from " + snap.storagePath());
        }

        // 3. Stamp every chunk with RAG metadata. Rebuild each Document via builder
        //    because Document metadata maps can be immutable depending on how the
        //    chunk was produced.
        List<Document> enriched = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document c = chunks.get(i);
            Map<String, Object> meta = new LinkedHashMap<>(c.getMetadata() == null ? Map.of() : c.getMetadata());
            meta.put("file_id", snap.id().toString());
            meta.put("file_name", snap.displayName());
            meta.put("file_type", snap.fileType());
            meta.put("chunk_index", i);
            enriched.add(new Document(c.getText(), meta));
        }

        // 4. Upsert in batches.
        for (int from = 0; from < enriched.size(); from += EMBED_BATCH_SIZE) {
            int to = Math.min(from + EMBED_BATCH_SIZE, enriched.size());
            vectorStore.add(enriched.subList(from, to));
        }

        return enriched.size();
    }

    private void safelyMarkFailed(UUID fileId, String message) {
        try {
            stateService.markFailed(fileId, message);
        } catch (Exception stateErr) {
            log.error("Could not even mark file {} as FAILED: {}", fileId, stateErr.getMessage(), stateErr);
        }
    }
}
