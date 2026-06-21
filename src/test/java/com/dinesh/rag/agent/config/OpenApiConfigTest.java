package com.dinesh.rag.agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void ragAgentOpenApi_exposesMetadata() {
        OpenAPI openApi = new OpenApiConfig().ragAgentOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("RAG Agent API");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("0.1.0");
        assertThat(openApi.getInfo().getDescription()).contains("Retrieval-Augmented Generation");
        assertThat(openApi.getInfo().getContact().getEmail()).isEqualTo("dineshedu944@gmail.com");
        assertThat(openApi.getInfo().getContact().getName()).isEqualTo("Dinesh");
    }
}
