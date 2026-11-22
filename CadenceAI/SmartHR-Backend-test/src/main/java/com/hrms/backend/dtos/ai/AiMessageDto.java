package com.hrms.backend.dtos.ai;

/** One chat bubble in the stored history. role is "USER" or "ASSISTANT"; timestamp is ISO UTC. */
public record AiMessageDto(String role, String text, String timestamp) {
}
