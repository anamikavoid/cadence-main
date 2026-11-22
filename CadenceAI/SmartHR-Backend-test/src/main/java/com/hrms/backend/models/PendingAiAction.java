package com.hrms.backend.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An action the assistant has PROPOSED but not performed yet, waiting for the user to say yes/no.
 *
 * SECURITY: this is stored on the SERVER (inside the user's AiConversation). The phone only ever
 * sends back "confirm/decline" plus this action's id, never the action's contents. So a modified
 * app cannot trick the server into performing a different action than the one it proposed.
 */
@Getter
@Setter
@NoArgsConstructor
public class PendingAiAction {
    private String id;
    /** CREATE_EVENT, UPDATE_EVENT or CANCEL_EVENT. */
    private String type;
    /** For UPDATE_EVENT / CANCEL_EVENT: the event (already verified to belong to this user). */
    private String targetEventId;
    // The final, fully validated values to apply (not used for CANCEL_EVENT).
    private String title;
    private String description;
    private String category;
    private String priority;
    private String location;
    private Instant startTime;
    private Instant endTime;
    /** Human-readable description shown to the user and given to Gemini as context. */
    private String summary;
    private Instant createdAt;
}
