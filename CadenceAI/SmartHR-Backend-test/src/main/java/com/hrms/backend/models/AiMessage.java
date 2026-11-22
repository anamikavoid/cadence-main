package com.hrms.backend.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One line of the user's conversation with the assistant. Embedded inside AiConversation. */
@Getter
@Setter
@NoArgsConstructor
public class AiMessage {
    /** "USER" or "ASSISTANT". */
    private String role;
    private String text;
    private Instant timestamp;

    public AiMessage(String role, String text, Instant timestamp) {
        this.role = role;
        this.text = text;
        this.timestamp = timestamp;
    }
}
