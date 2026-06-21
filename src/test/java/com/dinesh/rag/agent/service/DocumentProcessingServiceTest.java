package com.dinesh.rag.agent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DocumentProcessingServiceTest {

    @Mock
    VectorStore vectorStore;

    DocumentProcessingService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new DocumentProcessingService(vectorStore);
    }

    @Test
    void smallFile_flushesRemainderOnce() throws Exception {
        File file = writeLines("small.txt", 3);

        service.processFile(file, "small.txt");

        verify(vectorStore, times(1)).add(anyList());
        assertThat(file.exists()).isFalse();
    }

    @Test
    void fileAtExactBatchBoundary_flushesOnlyInLoop() throws Exception {
        File file = writeLines("exact.txt", 500);

        service.processFile(file, "exact.txt");

        verify(vectorStore, times(1)).add(anyList());
    }

    @Test
    void fileWithBatchAndRemainder_flushesTwice() throws Exception {
        File file = writeLines("large.txt", 750);

        service.processFile(file, "large.txt");

        verify(vectorStore, times(2)).add(anyList());
    }

    @Test
    void missingFile_throwsRuntimeException() {
        File missing = tempDir.resolve("missing.txt").toFile();

        assertThatThrownBy(() -> service.processFile(missing, "missing.txt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Error processing file");
    }

    private File writeLines(String name, int count) throws Exception {
        File file = tempDir.resolve(name).toFile();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append("line ").append(i).append('\n');
        }
        Files.writeString(file.toPath(), sb.toString());
        return file;
    }
}
