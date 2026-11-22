package com.hrms.backend.exceptions;

import org.springframework.http.HttpStatus;

/**
 * An error in the AI assistant that is safe to show to the user. The message must NEVER contain
 * secrets, stack traces or raw responses from Gemini; technical details go to the server log only.
 */
public class AiServiceException extends RuntimeException {
    private final HttpStatus status;

    public AiServiceException(HttpStatus status, String userMessage) {
        super(userMessage);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
