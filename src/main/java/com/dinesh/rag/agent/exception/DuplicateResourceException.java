package com.dinesh.rag.agent.exception;

/**
 * Thrown when an attempt to create a resource collides with an existing one
 * (e.g. duplicate email at registration, duplicate file by SHA-256).
 * Maps to HTTP 409 Conflict.
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
