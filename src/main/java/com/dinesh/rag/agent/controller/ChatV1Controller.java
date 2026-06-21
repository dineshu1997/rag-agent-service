package com.dinesh.rag.agent.controller;

import com.dinesh.rag.agent.dto.chat.ChatRequest;
import com.dinesh.rag.agent.dto.chat.ChatResponse;
import com.dinesh.rag.agent.dto.common.ErrorResponse;
import com.dinesh.rag.agent.service.chat.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
@Tag(name = "Chat", description = "Ask grounded questions about a previously uploaded file.")
public class ChatV1Controller {

    private final ChatService chatService;

    public ChatV1Controller(ChatService chatService) {
        this.chatService = chatService;
    }

    @Operation(
            summary = "Ask a question scoped to one file",
            description = "Performs a similarity search against the chunks of the given file, "
                    + "feeds the top matches as context to the local Ollama chat model, and "
                    + "returns the generated answer."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Generated answer",
                    content = @Content(schema = @Schema(implementation = ChatResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error", content = @Content(
                    schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "File not found", content = @Content(
                    schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "File is not READY yet", content = @Content(
                    schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ChatResponse ask(@Valid @RequestBody ChatRequest request) {
        return chatService.ask(request.fileId(), request.question());
    }
}
