package com.dinesh.rag.agent.exception;

/** Thrown when a resource lookup yields no row. Maps to HTTP 404 in the global handler. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String entity, Object id) {
        return new NotFoundException("%s not found: %s".formatted(entity, id));
    }
}
