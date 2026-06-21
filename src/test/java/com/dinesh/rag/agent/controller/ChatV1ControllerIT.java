package com.dinesh.rag.agent.controller;

import com.dinesh.rag.agent.AbstractIntegrationTest;
import com.dinesh.rag.agent.config.DataInitializer;
import com.dinesh.rag.agent.domain.file.FileEntity;
import com.dinesh.rag.agent.domain.file.FileRepository;
import com.dinesh.rag.agent.domain.file.FileStatus;
import com.dinesh.rag.agent.domain.file.FileType;
import com.dinesh.rag.agent.domain.user.User;
import com.dinesh.rag.agent.domain.user.UserRepository;
import com.dinesh.rag.agent.dto.chat.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for {@code POST /api/v1/chat}. Mocks the vector store and the
 * chat client so no Qdrant or Ollama is needed.
 */
class ChatV1ControllerIT extends AbstractIntegrationTest {

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private UserRepository userRepository;

    @Nested
    @DisplayName("POST /api/v1/chat")
    class Ask {

        @Test
        @DisplayName("READY file with hits → 200, answer")
        void happyPath() throws Exception {
            UUID fileId = seedFileWithStatus(FileStatus.READY);

            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of(
                            new Document("RAG stands for Retrieval-Augmented Generation.",
                                    Map.of("chunk_index", 0, "file_id", fileId.toString())),
                            new Document("It combines a retriever and an LLM.",
                                    Map.of("chunk_index", 1, "file_id", fileId.toString()))
                    ));
            when(chatClientBuilder.build().prompt()
                    .system(anyString()).user(anyString()).call().content())
                    .thenReturn("RAG = Retrieval-Augmented Generation.");

            mockMvc.perform(post("/api/v1/chat")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(
                                    new ChatRequest(fileId, "What is RAG?"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer", is("RAG = Retrieval-Augmented Generation.")))
                    .andExpect(jsonPath("$.citations").doesNotExist());
        }

        @Test
        @DisplayName("unknown fileId → 404")
        void unknownFile() throws Exception {
            mockMvc.perform(post("/api/v1/chat")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(
                                    new ChatRequest(UUID.randomUUID(), "anything"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("file still PENDING → 409")
        void notReadyPending() throws Exception {
            UUID fileId = seedFileWithStatus(FileStatus.PENDING);

            mockMvc.perform(post("/api/v1/chat")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(
                                    new ChatRequest(fileId, "anything"))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("file ingestion FAILED → 409")
        void notReadyFailed() throws Exception {
            UUID fileId = seedFileWithStatus(FileStatus.FAILED);

            mockMvc.perform(post("/api/v1/chat")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(
                                    new ChatRequest(fileId, "anything"))))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("zero hits → short-circuit canned answer, ChatClient untouched")
        void noHitsShortCircuits() throws Exception {
            UUID fileId = seedFileWithStatus(FileStatus.READY);

            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenReturn(List.of());

            mockMvc.perform(post("/api/v1/chat")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(
                                    new ChatRequest(fileId, "unrelated question"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer",
                            is("No relevant content was found in this document for that question.")));

            verify(chatClientBuilder.build().prompt(), never()).user(anyString());
        }

        @Test
        @DisplayName("blank question → 400")
        void blankQuestion() throws Exception {
            UUID fileId = seedFileWithStatus(FileStatus.READY);
            String body = """
                    { "fileId": "%s", "question": "" }
                    """.formatted(fileId);

            mockMvc.perform(post("/api/v1/chat")
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("missing fileId → 400")
        void missingFileId() throws Exception {
            mockMvc.perform(post("/api/v1/chat")
                            .contentType("application/json")
                            .content("{\"question\":\"hello?\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ----- helpers ------------------------------------------------------

    private UUID seedFileWithStatus(FileStatus status) {
        User anonymous = userRepository.findByEmailIgnoreCase(DataInitializer.ANONYMOUS_EMAIL)
                .orElseThrow(() -> new IllegalStateException("anonymous user not seeded"));

        FileEntity file = FileEntity.builder()
                .originalFilename("notes.txt")
                .displayName("notes.txt")
                .fileType(FileType.TXT)
                .sizeBytes(100)
                .sha256Hash(uniqueHash())
                .storagePath("./data/files/test-" + UUID.randomUUID() + ".txt")
                .createdBy(anonymous)
                .status(status)
                .chunkCount(status == FileStatus.READY ? 2 : 0)
                .build();
        return fileRepository.save(file).getId();
    }

    /** 64-char hash. UNIQUE on files.sha256_hash, so make every test row distinct. */
    private static String uniqueHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
    }
}
