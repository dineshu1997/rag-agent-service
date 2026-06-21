package com.dinesh.rag.agent.dto.auth;

import java.time.Instant;

public record AuthResponse(
        String token,
        Instant expiresAt,
        UserSummary user
) {

    public record UserSummary(
            java.util.UUID id,
            String email,
            String displayName
    ) {}
}
