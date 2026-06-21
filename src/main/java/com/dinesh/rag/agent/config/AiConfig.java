package com.dinesh.rag.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the shared {@link ChatClient} once from Spring AI's autoconfigured
 * {@link ChatClient.Builder}. A {@code ChatClient} is immutable and thread-safe
 * after {@code build()}, so it's a singleton bean rather than something we
 * reconstruct per request.
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
