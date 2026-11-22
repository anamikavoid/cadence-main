package com.hrms.backend.dtos.ai;

/** The part of a pending action the app is allowed to see. */
public record PendingActionDto(String id, String type, String summary) {
}
