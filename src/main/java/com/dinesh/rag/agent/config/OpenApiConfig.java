package com.dinesh.rag.agent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI ragAgentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("RAG Agent API")
                        .version("0.1.0")
                        .description("""
                                Local Ollama-backed Retrieval-Augmented Generation service.

                                Upload documents via `/api/v1/files`, then chat against them.
                                Every request (except `/api/v1/auth/**` and Swagger) requires a
                                `Bearer` JWT obtained from `/api/v1/auth/login`.
                                """)
                        .contact(new Contact()
                                .name("Dinesh")
                                .email("dineshedu944@gmail.com")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Paste the JWT from /api/v1/auth/login")));
    }
}
