package com.hrms.backend.exceptions;

/** A requested calendar write conflicts with the current schedule. */
public class EventConflictException extends BadApiRequestException {
    public EventConflictException(String message) {
        super(message);
    }
}
