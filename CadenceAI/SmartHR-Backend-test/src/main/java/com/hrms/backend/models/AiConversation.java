package com.hrms.backend.models;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The assistant conversation of ONE user (there is exactly one document per user).
 *
 * RETENTION LIMITS (so we never keep or send unlimited history):
 *  - only the last 20 messages are stored,
 *  - only the last 8 are sent to Gemini,
 *  - if the user is idle for 24 hours the context is reset (see AiChatService),
 *  - MongoDB deletes the whole document 7 days after its last update (TTL index below; needs
 *    spring.data.mongodb.auto-index-creation=true).
 */
@Getter
@Setter
@NoArgsConstructor
@Document(collection = "aiConversations")
public class AiConversation {
    @Id
    private String id;

    @Indexed(unique = true)
    private String userId;

    private List<AiMessage> messages = new ArrayList<>();

    private PendingAiAction pendingAction;

    /** The event most recently created/updated, so "move it to 7 PM" knows what "it" is. */
    private String lastEventId;

    /**
     * Optimistic-locking counter: Spring increments it on every save and refuses a save made from
     * a stale copy. We use it so a double-tapped "Confirm" cannot execute an action twice.
     */
    @Version
    private Long version;

    @Indexed(expireAfterSeconds = 604800)
    private Instant updatedAt;
}
