package com.dinesh.rag.agent.exception;

/** Thrown when login credentials are invalid. Maps to HTTP 401. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
