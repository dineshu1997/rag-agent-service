package com.dinesh.rag.agent.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DocumentProcessingService {

    private final VectorStore vectorStore;

    public DocumentProcessingService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // REMOVED @Async - This now runs on the main HTTP thread
    public void processFile(File savedFile, String originalFilename) {
        try {
            System.out.println("Started processing: " + originalFilename);

            processInBatches(savedFile, originalFilename);

            savedFile.delete();
            System.out.println("Finished processing: " + originalFilename);

        } catch (Exception e) {
            throw new RuntimeException("Error processing file: " + e.getMessage(), e);
        }
    }

    private void processInBatches(File file, String originalFilename) throws Exception {
        TokenTextSplitter splitter = TokenTextSplitter.builder().build();
        StringBuilder currentTextBatch = new StringBuilder();

        int lineCount = 0;
        int BATCH_SIZE = 500;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                currentTextBatch.append(line).append("\n");
                lineCount++;

                if (lineCount % BATCH_SIZE == 0) {
                    Document doc = new Document(currentTextBatch.toString(), Map.of("filename", originalFilename));
                    vectorStore.add(splitter.apply(List.of(doc)));
                    currentTextBatch.setLength(0);
                    System.out.println("Processed " + lineCount + " lines...");
                }
            }

            if (!currentTextBatch.isEmpty()) {
                Document doc = new Document(currentTextBatch.toString(), Map.of("filename", originalFilename));
                vectorStore.add(splitter.apply(List.of(doc)));
            }
        }
    }
}