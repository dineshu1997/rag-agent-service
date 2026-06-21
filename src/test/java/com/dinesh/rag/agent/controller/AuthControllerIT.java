package com.dinesh.rag.agent.controller;

import com.dinesh.rag.agent.AbstractIntegrationTest;
import com.dinesh.rag.agent.dto.auth.LoginRequest;
import com.dinesh.rag.agent.dto.auth.RegisterRequest;
import com.dinesh.rag.agent.service.auth.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code /api/v1/auth/register} and
 * {@code /api/v1/auth/login}. Covers happy paths plus the main failure
 * modes (validation, duplicate email, wrong credentials).
 */
class AuthControllerIT extends AbstractIntegrationTest {

    @Nested
    @DisplayName("POST /api/v1/auth/register")
    class Register {

        @Test
        @DisplayName("creates user and returns JWT")
        void registerSuccess() throws Exception {
            RegisterRequest body = new RegisterRequest("alice@example.com", "password123", "Alice");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.token", is(notNullValue())))
                    .andExpect(jsonPath("$.expiresAt", is(notNullValue())))
                    .andExpect(jsonPath("$.user.id", is(notNullValue())))
                    .andExpect(jsonPath("$.user.email", equalTo("alice@example.com")))
                    .andExpect(jsonPath("$.user.displayName", equalTo("Alice")));
        }

        @Test
        @DisplayName("rejects duplicate email with 409")
        void duplicateEmail() throws Exception {
            register("dup@example.com", "password123", "Dup");

            RegisterRequest body = new RegisterRequest("dup@example.com", "anotherpass", "Other");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status", is(409)));
        }

        @Test
        @DisplayName("rejects bad email with 400")
        void badEmail() throws Exception {
            RegisterRequest body = new RegisterRequest("not-an-email", "password123", "Bob");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]", is(notNullValue())));
        }

        @Test
        @DisplayName("rejects short password with 400")
        void shortPassword() throws Exception {
            RegisterRequest body = new RegisterRequest("c@example.com", "short", "Carol");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]", is(notNullValue())));
        }

        @Test
        @DisplayName("rejects missing displayName with 400")
        void missingDisplayName() throws Exception {
            RegisterRequest body = new RegisterRequest("d@example.com", "password123", "");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class Login {

        @Test
        @DisplayName("returns JWT for valid creds")
        void loginSuccess() throws Exception {
            register("eve@example.com", "password123", "Eve");

            LoginRequest body = new LoginRequest("eve@example.com", "password123");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token", is(notNullValue())))
                    .andExpect(jsonPath("$.user.email", equalTo("eve@example.com")));
        }

        @Test
        @DisplayName("email lookup is case-insensitive")
        void loginCaseInsensitive() throws Exception {
            register("fred@example.com", "password123", "Fred");

            LoginRequest body = new LoginRequest("FRED@EXAMPLE.COM", "password123");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("wrong password returns 401")
        void wrongPassword() throws Exception {
            register("grace@example.com", "password123", "Grace");

            LoginRequest body = new LoginRequest("grace@example.com", "wrong-password");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status", is(401)));
        }

        @Test
        @DisplayName("unknown email returns 401 (no user-enumeration leak)")
        void unknownEmail() throws Exception {
            LoginRequest body = new LoginRequest("ghost@example.com", "password123");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("JWT filter — stale token handling")
    class StaleToken {

        @Autowired
        private JwtService jwtService;

        /**
         * Regression: a perfectly valid, correctly-signed token whose subject is a
         * user that no longer exists (deleted, or DB reset) used to throw
         * {@code UsernameNotFoundException} out of the filter — which escaped the
         * {@code catch (JwtException | IllegalArgumentException)} block and surfaced
         * as a raw 500. The filter must instead treat it as unauthenticated and let
         * the request continue (degrading to the anonymous fallback on the currently
         * open chain), never 500.
         */
        @Test
        @DisplayName("valid token for a non-existent user does NOT 500")
        void staleTokenDegradesGracefully() throws Exception {
            String ghostToken = jwtService.issue(UUID.randomUUID(), "ghost@example.com").token();

            mockMvc.perform(get("/api/v1/files")
                            .header(HttpHeaders.AUTHORIZATION, bearer(ghostToken)))
                    .andExpect(status().isOk());
        }
    }
}
