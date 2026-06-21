package com.dinesh.rag.agent.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Strongly-typed bindings for the {@code app.*} keys in {@code application.yaml}.
 *
 * <p>Using {@code @ConfigurationProperties} (rather than scattered {@code @Value}
 * lookups) gives us validation at startup and IDE-friendly access elsewhere.</p>
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Jwt jwt,
        Files files,
        Chat chat
) {

    public record Jwt(
            @NotBlank String secret,
            @Min(1) long expirationMinutes,
            @NotBlank String issuer
    ) {}

    public record Files(
            @NotBlank String storagePath,
            @NotEmpty List<String> allowedExtensions
    ) {}

    /**
     * Retrieval knobs for {@code /api/v1/chat}.
     *
     * @param topK                how many chunks to consider per question
     * @param similarityThreshold minimum cosine similarity (0..1) for a chunk
     *                            to count as a hit. Chunks below the threshold
     *                            are dropped before they reach the LLM, so a
     *                            question whose best match is still weak gets
     *                            the canned "no relevant content" response
     *                            instead of an answer grounded in noise.
     */
    public record Chat(
            @Min(1) int topK,
            @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold
    ) {}
}
