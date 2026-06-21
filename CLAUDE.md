# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build the project
./gradlew build

# Run the application (depends on :startPrereqs — verifies/starts Qdrant, Postgres, Ollama)
./gradlew bootRun

# Build without running the test suite
./gradlew build -x test

# Run tests
./gradlew test

# Clean build outputs
./gradlew clean
```

## Prerequisites

The app needs THREE local dependencies up before `bootRun`: **Ollama** (+ models), **PostgreSQL**, and **Qdrant** (+ the collection).

### One-liner setup

`bootRun` automatically runs `:startPrereqs` first, so in the normal case you just:

```bash
./gradlew bootRun
```

`:startPrereqs` executes `src/main/resources/script/start-prereqs.sh`, which is
**verify-first**: it probes each dependency on its expected port and only starts
the missing ones (Postgres/Qdrant via `docker-compose.yml`, Ollama via
`ollama serve`), pulls any missing models, and ensures the Qdrant collection
exists. It exits non-zero (blocking the app) if a dependency can't be satisfied.
Run it standalone any time with:

```bash
bash src/main/resources/script/start-prereqs.sh   # or: ./gradlew startPrereqs
```

Override defaults via env vars: `CHAT_MODEL`, `EMBED_MODEL`, `OLLAMA_HOST`,
`QDRANT_URL`, `QDRANT_COLLECTION`, `VECTOR_SIZE`, `PG_HOST`, `PG_PORT`,
`WAIT_SECONDS`, `SKIP_DOCKER`. `bash` must be on PATH for the Gradle task — Git
Bash provides it on Windows.

Because the script only starts a dependency when it's actually down, it coexists
fine with a **native** Postgres or a manually-run Qdrant container — those just
pass the probe and are left untouched.

### PostgreSQL

Started by `docker-compose.yml` (image `postgres:16-alpine`, db `rag_agent`, port 5432). Default credentials match `application.yaml`: user `postgres`, pass `admin`. Override via env vars `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.

Schema is owned by **Flyway migrations** under `src/main/resources/db/migration` (`V1__init_schema.sql` creates `users` and `files`). Hibernate is `ddl-auto: validate` — it only verifies that JPA entities still line up with the live schema and never issues DDL. Application code never has control over the database schema; all changes go through a new versioned migration (`V2__…`, `V3__…`).

Workflow for a schema change:
1. Add a new `V{n}__description.sql` under `src/main/resources/db/migration`.
2. Update the matching JPA entity / repository.
3. Restart — Flyway applies the migration, Hibernate validates.

If you need to start clean (e.g. you mutated V1 during early development), drop the tables manually and let Flyway recreate them on next boot:
```bash
psql -h localhost -U postgres -d rag_agent \
  -c "DROP TABLE IF EXISTS files CASCADE; DROP TABLE IF EXISTS users CASCADE; DROP TABLE IF EXISTS flyway_schema_history CASCADE;"
```

### Ollama

Ollama must be running locally on port 11434 with two models pulled:
- `llama3.2` — chat/generation model
- `nomic-embed-text` — embedding model (768 dims)

These are checked/started by `:startPrereqs` (see Prerequisites above) — it starts `ollama serve` if down and pulls either model if missing.

### Qdrant

Now started by `docker-compose.yml` (image `qdrant/qdrant:latest`, ports 6333 REST + 6334 gRPC). Spring AI's Qdrant client talks **gRPC on 6334**. The REST port (6333) is only needed for manual collection management and the dashboard.

The collection must exist before the app starts (`initialize-schema: false` in `application.yaml`). `:startPrereqs` creates it automatically if missing (size 768, Cosine — matches `nomic-embed-text`). To do it by hand:
```bash
curl -X PUT http://localhost:6333/collections/my-rag-agent \
  -H "Content-Type: application/json" \
  -d '{"vectors": {"size": 768, "distance": "Cosine"}}'
```

### File storage

Uploaded blobs are persisted to disk at `app.files.storage-path` (default `./data/files`, overridable via `APP_FILES_STORAGE_PATH`). The directory is created on startup. Each blob is named `{file_id}{ext}`.

### JWT

Auth uses HS256 signed JWTs. `app.jwt.secret` MUST be at least 32 bytes (256 bits); the dev default in `application.yaml` is a placeholder. Override via `APP_JWT_SECRET` in any non-toy environment. Tokens default to 240-minute expiration (`APP_JWT_EXPIRATION_MINUTES`).

## Architecture

Spring Boot 4.0.5 / Spring AI 2.0.0-M4 RAG application backed entirely by local Ollama models. No external LLM API dependency.

### High-level pieces

- **Shared file corpus** stored in PostgreSQL (`files` table) — any authenticated user can query any file. Only the uploader (`created_by`) can delete a file.
- **Per-user identity** via Spring Security + JWT (`users` table, `AuthController`, `JwtAuthenticationFilter`).
- **Async ingestion** on a bounded executor (`ingestionExecutor`, 2 threads) — uploads return `202` immediately and the worker parses (Tika), chunks (`TokenTextSplitter`), embeds (Ollama `nomic-embed-text`), and upserts into Qdrant.
- **Single Qdrant collection** with rich payload metadata (`file_id`, `file_name`, `chunk_index`, `file_type`). Scoped vs global retrieval is just whether the query filters on `file_id`.

### Package layout

```
com.dinesh.rag.agent
├── config/        OpenApi, Security (+CORS), Async, AppProperties, DataInitializer, AiConfig
├── controller/    Auth, Files, ChatV1, GlobalExceptionHandler
├── domain/
│   ├── user/      User entity, UserRepository
│   └── file/      FileEntity, FileStatus, FileType, FileRepository
├── service/
│   ├── auth/      AuthService, JwtService, JwtAuthenticationFilter,
│   │              UserDetailsServiceImpl, CurrentUserService
│   ├── file/      FileService, FileStorageService, FileIngestionService,
│   │              FileIngestionStateService, IngestionTrigger (+Async/Immediate impls)
│   └── chat/      ChatService (retrieve-then-generate over one file)
├── dto/
│   ├── auth/      RegisterRequest, LoginRequest, AuthResponse
│   ├── file/      FileUploadResponse, FileResponse, FileStatusResponse
│   ├── chat/      ChatRequest, ChatResponse
│   └── common/    ErrorResponse, PageResponse
└── exception/     NotFoundException, DuplicateResourceException,
                   UnsupportedFileTypeException, InvalidCredentialsException,
                   FileNotReadyException
```

### Files API

| Method | Path | Description |
|--------|------|-------------|
| POST   | `/api/v1/auth/register`       | Create user, return JWT |
| POST   | `/api/v1/auth/login`          | Validate creds, return JWT |
| POST   | `/api/v1/files`               | Multipart upload; returns 202 with file id and `PENDING` status |
| GET    | `/api/v1/files?q=&page=&size=`| Paged list; case-insensitive search by display name |
| GET    | `/api/v1/files/{id}`          | Full file metadata |
| GET    | `/api/v1/files/{id}/status`   | Cheap poll (status + chunk count + error) |
| DELETE | `/api/v1/files/{id}`          | Removes vectors + DB row + blob (uploader only) |
| POST   | `/api/v1/chat`                | RAG question over one file; returns `{ answer }` |

`POST /api/v1/chat` takes `{ fileId, question }`. It looks up the file (404 if
missing, 409 if not `READY`), runs a similarity search scoped to that
`file_id` (topK + similarity-threshold from `app.chat.*`), and — if any chunk
clears the threshold — feeds those chunks to the Ollama chat model with a
strict "answer only from context" system prompt. Zero hits short-circuits to a
canned "no relevant content" answer without calling the LLM. See `ChatService`.

The earlier legacy `GET /chat` and `/load-data-file` endpoints have been
removed now that `/api/v1/chat` is in place.

### Ingestion pipeline (POST `/api/v1/files`)

1. `FileService.upload` validates extension, writes the blob via `FileStorageService` (computing SHA-256 in the same streaming pass), inserts a `files` row with `status=PENDING`, and kicks off ingestion through `IngestionTrigger`. In prod, `AsyncIngestionTrigger` defers the `@Async` `FileIngestionService.ingest(fileId)` call until *after* the upload transaction commits (so the worker is guaranteed to see the row); in the `test` profile, `ImmediateIngestionTrigger` calls it synchronously so Mockito can verify it.
2. The worker (`ingestionExecutor` thread):
   - Reads the file row via `FileIngestionStateService.loadForIngest` (separate `REQUIRES_NEW` Tx, no LAZY surprises).
   - Marks `PROCESSING`.
   - Parses with Spring AI's `TikaDocumentReader` (handles PDF, DOCX, MD, TXT, code files via Tika).
   - Splits with `TokenTextSplitter` (default chunk size).
   - Stamps each chunk with metadata: `file_id`, `file_name`, `file_type`, `chunk_index`.
   - Batches `vectorStore.add(...)` in groups of 16 to keep Ollama embedding latency stable.
   - Marks `READY` with `chunk_count`, or `FAILED` with a truncated error message.
3. DELETE removes vectors via `vectorStore.delete(Filter.Expression)` filtered on `file_id`, then the row, then the blob.

### Vector store

Qdrant, auto-configured by `spring-ai-starter-vector-store-qdrant`. The `VectorStore` bean is wired by the starter from `spring.ai.vectorstore.qdrant.*` properties; there is no manual `@Configuration` class.

## Key constraints

- Qdrant collection must exist before the app starts (`initialize-schema: false`). `:startPrereqs` auto-creates it; see the Qdrant section.
- Java 24 toolchain is required (configured in `build.gradle`).
- `RagAgentApplication` is annotated `@EnableAsync` and the async pool is defined in `AsyncConfig` (`ingestionExecutor` bean).
- Schema is Flyway-owned with Hibernate in `validate` mode. Never set `ddl-auto: create/update` in `application.yaml` — write a new `V{n}` migration instead.

## Testing

JUnit integration tests cover the Auth, Files, and Chat APIs with **no external
services required**:

```bash
./gradlew test
```

`AuthControllerIT`, `FilesControllerIT`, and `ChatV1ControllerIT`
(`src/test/java/.../controller`) extend `AbstractIntegrationTest`, which boots the
full Spring context against the `test` profile (`src/test/resources/application-test.yaml`):
- H2 in PostgreSQL compatibility mode, Flyway disabled, Hibernate `ddl-auto: create-drop`.
- `VectorStore`, `FileIngestionService`, and `ChatClient` are replaced with `@MockitoBean` mocks — no Qdrant or Ollama needed.
- MockMvc + the real Spring Security filter chain, so JWT auth is exercised exactly as in prod.

Coverage:
- Register (success, dup email, validation errors).
- Login (success, case-insensitive email, wrong password, unknown user).
- JWT filter: a valid token for a since-deleted user degrades gracefully (no 500).
- Upload (no auth, empty, unsupported type, success → 202 with `ingest()` verified, duplicate SHA → 409).
- List + search with pagination, shared-corpus visibility.
- GET by id + status endpoint.
- DELETE by uploader vs. non-uploader (403) vs. unknown id (404).
- Chat (`/api/v1/chat`): READY file answers, 404 unknown file, 409 not-ready, zero-hit short-circuit, validation 400s.

There is no checked-in end-to-end smoke script; for live testing hit the endpoints
with `curl`/Swagger against a running `bootRun` stack.

## API

Swagger UI: `http://localhost:8080/swagger-ui.html` (via springdoc-openapi 3.0.2). The "Authorize" button takes a JWT issued by `/api/v1/auth/login`.
