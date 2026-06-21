package com.dinesh.rag.agent.exception;

/** Thrown when an upload's extension/content type isn't in the allow-list. Maps to HTTP 415. */
public class UnsupportedFileTypeException extends RuntimeException {

    public UnsupportedFileTypeException(String message) {
        super(message);
    }
}
