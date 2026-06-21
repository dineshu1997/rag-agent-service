-- Initial schema for rag-agent. Owned by Flyway; Hibernate runs in `validate` mode.

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255)             NOT NULL,
    password_hash VARCHAR(255)             NOT NULL,
    display_name  VARCHAR(255)             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE files (
    id                UUID PRIMARY KEY,
    original_filename VARCHAR(512)             NOT NULL,
    display_name      VARCHAR(512)             NOT NULL,
    file_type         VARCHAR(32)              NOT NULL,
    size_bytes        BIGINT                   NOT NULL,
    sha256_hash       VARCHAR(64)              NOT NULL,
    storage_path      VARCHAR(1024)            NOT NULL,
    created_by        UUID                     NOT NULL,
    status            VARCHAR(32)              NOT NULL,
    chunk_count       INTEGER                  NOT NULL,
    error_message     TEXT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_files_sha256_hash UNIQUE (sha256_hash),
    CONSTRAINT fk_files_created_by  FOREIGN KEY (created_by) REFERENCES users (id),
    CONSTRAINT ck_files_file_type   CHECK (file_type IN ('PDF', 'DOCX', 'DOC', 'TXT', 'MD', 'CODE', 'OTHER')),
    CONSTRAINT ck_files_status      CHECK (status    IN ('PENDING', 'PROCESSING', 'READY', 'FAILED'))
);

CREATE INDEX idx_files_display_name_lower ON files (LOWER(display_name));
CREATE INDEX idx_files_created_by         ON files (created_by);
CREATE INDEX idx_files_created_at_desc    ON files (created_at DESC);
