package com.hrms.backend.exceptions;

import com.hrms.backend.dtos.response_message.ErrorApiResponseMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns an AiServiceException into an HTTP response with the same JSON shape the rest of this
 * backend uses: {"error": "message"}. The Android app reads the "error" field.
 */
@RestControllerAdvice
public class AiExceptionHandler {

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ErrorApiResponseMessage> handleAiServiceException(AiServiceException ex) {
        ErrorApiResponseMessage body = ErrorApiResponseMessage.builder().error(ex.getMessage()).build();
        return new ResponseEntity<>(body, ex.getStatus());
    }
}
