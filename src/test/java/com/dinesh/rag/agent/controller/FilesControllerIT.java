package com.dinesh.rag.agent.controller;

import com.dinesh.rag.agent.AbstractIntegrationTest;
import com.dinesh.rag.agent.dto.auth.AuthResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration tests for {@code /api/v1/files/**}. Covers auth,
 * upload validation, listing/search, lookup, status, and delete (including
 * the uploader-only authorization rule).
 */
class FilesControllerIT extends AbstractIntegrationTest {

    private static final String EMAIL_A = "alice@example.com";
    private static final String EMAIL_B = "bob@example.com";
    private static final String PASSWORD = "password123";

    // --------------------------------------------------------------------
    // POST /api/v1/files
    // --------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/v1/files")
    class Upload {

        @Test
        @DisplayName("without JWT → 202 (open backend, attributed to anonymous user)")
        void unauthenticatedSucceedsAsAnonymous() throws Exception {
            MockMultipartFile file = textFile("note.txt", "hello world");

            mockMvc.perform(multipart("/api/v1/files").file(file))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status", equalTo("PENDING")));
        }

        @Test
        @DisplayName("empty file → 400")
        void emptyFile() throws Exception {
            String token = registerAndLogin(EMAIL_A);
            MockMultipartFile empty = new MockMultipartFile(
                    "file", "blank.txt", "text/plain", new byte[0]);

            mockMvc.perform(multipart("/api/v1/files")
                            .file(empty)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("unsupported extension → 415")
        void unsupportedType() throws Exception {
            String token = registerAndLogin(EMAIL_A);
            MockMultipartFile exe = new MockMultipartFile(
                    "file", "trojan.exe", "application/octet-stream", "MZ\0\0".getBytes());

            mockMvc.perform(multipart("/api/v1/files")
                            .file(exe)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("valid upload → 202 + PENDING, ingest called")
        void uploadSuccess() throws Exception {
            String token = registerAndLogin(EMAIL_A);
            MockMultipartFile file = textFile("notes.txt", "the quick brown fox");

            MvcResult res = mockMvc.perform(multipart("/api/v1/files")
                            .file(file)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.id", is(notNullValue())))
                    .andExpect(jsonPath("$.displayName", equalTo("notes.txt")))
                    .andExpect(jsonPath("$.status", equalTo("PENDING")))
                    .andReturn();

            UUID id = extractUuid(res, "$.id");
            verify(fileIngestionService).ingest(id);
        }

        @Test
        @DisplayName("identical content uploaded twice → 409 on second")
        void duplicateContent() throws Exception {
            String token = registerAndLogin(EMAIL_A);
            MockMultipartFile first = textFile("a.txt", "same content");
            MockMultipartFile second = textFile("b.txt", "same content");

            mockMvc.perform(multipart("/api/v1/files")
                            .file(first)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isAccepted());

            mockMvc.perform(multipart("/api/v1/files")
                            .file(second)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isConflict());
        }
    }

    // --------------------------------------------------------------------
    // GET /api/v1/files  (list + search)
    // --------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/v1/files")
    class List {

        @Test
        @DisplayName("without JWT → 200 (open backend)")
        void unauthenticatedSucceeds() throws Exception {
            mockMvc.perform(get("/api/v1/files"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("returns paged results")
        void paging() throws Exception {
            String token = registerAndLogin(EMAIL_A);

            uploadText(token, "doc-1.txt", "alpha content");
            uploadText(token, "doc-2.txt", "beta content");
            uploadText(token, "doc-3.txt", "gamma content");

            mockMvc.perform(get("/api/v1/files")
                            .param("page", "0").param("size", "2")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()", is(2)))
                    .andExpect(jsonPath("$.totalItems", is(greaterThanOrEqualTo(3))))
                    .andExpect(jsonPath("$.hasNext", is(true)));
        }

        @Test
        @DisplayName("q filters by display name (case-insensitive)")
        void searchByName() throws Exception {
            String token = registerAndLogin(EMAIL_A);

            uploadText(token, "DBMS-notes.txt", "one");
            uploadText(token, "english-essay.txt", "two");

            mockMvc.perform(get("/api/v1/files")
                            .param("q", "dbms")
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[*].displayName", hasItem("DBMS-notes.txt")))
                    .andExpect(jsonPath("$.totalItems", is(1)));
        }

        @Test
        @DisplayName("returns files uploaded by any user (shared corpus)")
        void sharedCorpus() throws Exception {
            String tokenA = registerAndLogin(EMAIL_A);
            String tokenB = registerAndLogin(EMAIL_B);

            uploadText(tokenA, "from-alice.txt", "alice's content");
            uploadText(tokenB, "from-bob.txt",   "bob's content");

            // Alice sees both files
            mockMvc.perform(get("/api/v1/files")
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                    .andExpect(jsonPath("$.items[*].displayName", hasItem("from-alice.txt")))
                    .andExpect(jsonPath("$.items[*].displayName", hasItem("from-bob.txt")));
        }
    }

    // --------------------------------------------------------------------
    // GET /api/v1/files/{id}  +  /status
    // --------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/v1/files/{id}")
    class GetOne {

        @Test
        @DisplayName("returns full metadata including uploader")
        void getById() throws Exception {
            String token = registerAndLogin(EMAIL_A);
            UUID id = uploadText(token, "lookup.txt", "find me");

            mockMvc.perform(get("/api/v1/files/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", equalTo(id.toString())))
                    .andExpect(jsonPath("$.displayName", equalTo("lookup.txt")))
                    .andExpect(jsonPath("$.fileType", equalTo("TXT")))
                    .andExpect(jsonPath("$.status", equalTo("PENDING")))
                    .andExpect(jsonPath("$.createdBy.email", equalTo(EMAIL_A)));
        }

        @Test
        @DisplayName("unknown id → 404")
        void notFound() throws Exception {
            String token = registerAndLogin(EMAIL_A);

            mockMvc.perform(get("/api/v1/files/{id}", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("/status returns lightweight payload")
        void statusEndpoint() throws Exception {
            String token = registerAndLogin(EMAIL_A);
            UUID id = uploadText(token, "polling.txt", "poll me");

            mockMvc.perform(get("/api/v1/files/{id}/status", id)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", equalTo(id.toString())))
                    .andExpect(jsonPath("$.status", equalTo("PENDING")))
                    .andExpect(jsonPath("$.chunkCount", is(0)));
        }
    }

    // --------------------------------------------------------------------
    // DELETE /api/v1/files/{id}
    // --------------------------------------------------------------------

    @Nested
    @DisplayName("DELETE /api/v1/files/{id}")
    class Delete {

        @Test
        @DisplayName("by uploader → 204 + vector cleanup invoked")
        void deleteByUploader() throws Exception {
            String token = registerAndLogin(EMAIL_A);
            UUID id = uploadText(token, "to-delete.txt", "ephemeral");

            mockMvc.perform(delete("/api/v1/files/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNoContent());

            // Vectors are cleaned up as part of delete
            verify(fileIngestionService).deleteVectorsForFile(id);

            // ...and the row is gone
            mockMvc.perform(get("/api/v1/files/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("by a different user → 403")
        void deleteByNonUploader() throws Exception {
            String tokenA = registerAndLogin(EMAIL_A);
            String tokenB = registerAndLogin(EMAIL_B);

            UUID id = uploadText(tokenA, "alice-only.txt", "private to alice");

            mockMvc.perform(delete("/api/v1/files/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
                    .andExpect(status().isForbidden());

            // And the file is still around
            mockMvc.perform(get("/api/v1/files/{id}", id)
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokenA)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("unknown id → 404")
        void deleteNotFound() throws Exception {
            String token = registerAndLogin(EMAIL_A);

            mockMvc.perform(delete("/api/v1/files/{id}", UUID.randomUUID())
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isNotFound());
        }
    }

    // --------------------------------------------------------------------
    // helpers
    // --------------------------------------------------------------------

    private static MockMultipartFile textFile(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/plain", content.getBytes());
    }

    /** Register + login + return JWT. Use a unique email per test class. */
    private String registerAndLogin(String email) throws Exception {
        AuthResponse auth = register(email, PASSWORD, email.substring(0, email.indexOf('@')));
        return auth.token();
    }

    private UUID uploadText(String token, String filename, String content) throws Exception {
        MvcResult res = mockMvc.perform(multipart("/api/v1/files")
                        .file(textFile(filename, content))
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isAccepted())
                .andReturn();
        return extractUuid(res, "$.id");
    }

    private UUID extractUuid(MvcResult res, String jsonPath) throws Exception {
        String json = res.getResponse().getContentAsString();
        com.jayway.jsonpath.DocumentContext ctx = com.jayway.jsonpath.JsonPath.parse(json);
        return UUID.fromString(ctx.read(jsonPath));
    }
}
