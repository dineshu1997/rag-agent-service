package com.dinesh.rag.agent;

import com.dinesh.rag.agent.dto.auth.AuthResponse;
import com.dinesh.rag.agent.dto.auth.LoginRequest;
import com.dinesh.rag.agent.dto.auth.RegisterRequest;
import com.dinesh.rag.agent.service.file.FileIngestionService;
import tools.jackson.databind.ObjectMapper;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared scaffolding for HTTP-level integration tests.
 *
 * <p>Boots the full Spring context against the {@code test} profile (H2,
 * Flyway off, Hibernate {@code create-drop}). External dependencies are
 * replaced with Mockito beans so tests never touch Ollama or Qdrant:</p>
 *
 * <ul>
 *   <li>{@link VectorStore} — replaced; no Qdrant calls.</li>
 *   <li>{@link FileIngestionService} — replaced; uploads succeed without
 *       actually running Tika / embeddings, and tests can verify
 *       {@code ingest()} / {@code deleteVectorsForFile()} were invoked.</li>
 *   <li>{@link ChatClient} — replaced with deep stubs so {@code ChatService}
 *       has no real Ollama dependency, and tests can stub chat responses
 *       directly on the same instance the service uses.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc   // gives us a MockMvc bean with the Spring Security filter chain already applied
@ActiveProfiles("test")
@Transactional          // Each test runs in a transaction that rolls back at the end —
                        // keeps tests isolated against the shared in-memory H2.
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @MockitoBean
    protected VectorStore vectorStore;

    @MockitoBean
    protected FileIngestionService fileIngestionService;

    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    protected ChatClient chatClient;

    // ----- auth helpers ------------------------------------------------

    protected AuthResponse register(String email, String password, String displayName) throws Exception {
        RegisterRequest req = new RegisterRequest(email, password, displayName);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(res.getResponse().getContentAsString(), AuthResponse.class);
    }

    protected AuthResponse login(String email, String password) throws Exception {
        LoginRequest req = new LoginRequest(email, password);
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(res.getResponse().getContentAsString(), AuthResponse.class);
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }
}
