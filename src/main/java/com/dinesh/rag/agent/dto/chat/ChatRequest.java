package com.dinesh.rag.agent.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ChatRequest(
        @NotNull                    UUID   fileId,
        @NotBlank @Size(max = 4000) String question
) {}
