package com.dinesh.rag.agent.service.chat;

import com.dinesh.rag.agent.config.AppProperties;
import com.dinesh.rag.agent.domain.file.FileEntity;
import com.dinesh.rag.agent.domain.file.FileRepository;
import com.dinesh.rag.agent.domain.file.FileStatus;
import com.dinesh.rag.agent.dto.chat.ChatResponse;
import com.dinesh.rag.agent.exception.FileNotReadyException;
import com.dinesh.rag.agent.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Retrieval-augmented question answering over a single uploaded file.
 *
 * <p>Flow per request:
 * <ol>
 *   <li>Look up the file row. 404 if missing, 409 if not {@link FileStatus#READY}.</li>
 *   <li>Similarity search Qdrant, scoped to {@code file_id == :fileId} so chunks
 *       from other files in the shared corpus never leak in.</li>
 *   <li>If zero hits, short-circuit with a canned "no relevant content" answer —
 *       saves the 30-60s of Ollama time it would take to generate the same thing.</li>
 *   <li>Otherwise stuff the retrieved chunks into the prompt as context and ask
 *       the chat model to answer ONLY from that context.</li>
 * </ol>
 *
 * <p>Stateless: every call is independent. Conversation memory / threads can be
 * added later without breaking the contract.</p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /**
     * Strict grounding instructions. Keep these in the system message so users
     * can't override them via the question.
     */
    private static final String SYSTEM_PROMPT = """
            You are answering questions strictly about the document provided in the context below.
            Rules:
              1. Use ONLY the context. Do not bring in outside knowledge.
              2. If the context does not contain the answer, reply exactly:
                 "I don't know based on this document."
              3. Be concise.
            """;

    private static final String NO_CONTEXT_ANSWER =
            "No relevant content was found in this document for that question.";

    private final FileRepository fileRepository;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final AppProperties.Chat chatProps;

    public ChatService(FileRepository fileRepository,
                       VectorStore vectorStore,
                       ChatClient chatClient,
                       AppProperties appProperties) {
        this.fileRepository = fileRepository;
        this.vectorStore = vectorStore;
        // Injected as a singleton (built once in AiConfig) — immutable and
        // thread-safe, so it's reused across every request rather than rebuilt.
        this.chatClient = chatClient;
        this.chatProps = appProperties.chat();
    }

    public ChatResponse ask(UUID fileId, String question) {
        FileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> NotFoundException.of("File", fileId));

        if (file.getStatus() != FileStatus.READY) {
            throw FileNotReadyException.of(fileId, file.getStatus());
        }

        List<Document> hits = retrieve(fileId, question);
        log.debug("Chat retrieve: fileId={} question='{}' hits={}", fileId, question, hits.size());

        if (hits.isEmpty()) {
            return new ChatResponse(NO_CONTEXT_ANSWER);
        }

        String answer = generate(question, hits);
        return new ChatResponse(answer);
    }

    // ----- internals ---------------------------------------------------

    private List<Document> retrieve(UUID fileId, String question) {
        var filter = new FilterExpressionBuilder()
                .eq("file_id", fileId.toString())
                .build();

        // similarityThreshold(double) filters out weak matches at the vector
        // store level — so the LLM never sees them. Threshold + topK are both
        // surfaced as config so we can tune per embedding model without a redeploy.
        SearchRequest req = SearchRequest.builder()
                .query(question)
                .topK(chatProps.topK())
                .similarityThreshold(chatProps.similarityThreshold())
                .filterExpression(filter)
                .build();

        List<Document> result = vectorStore.similaritySearch(req);
        return result == null ? List.of() : result;
    }

    private String generate(String question, List<Document> hits) {
        String context = hits.stream()
                .map(Document::getText)
                .reduce((a, b) -> a + "\n---\n" + b)
                .orElse("");

        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("Context:\n" + context + "\n\nQuestion: " + question)
                .call()
                .content();
    }
}
