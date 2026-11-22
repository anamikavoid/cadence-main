package com.hrms.backend.services.aiService;

import com.hrms.backend.dtos.ai.AiStatus;
import com.hrms.backend.models.Event;
import com.hrms.backend.models.PendingAiAction;

import java.util.List;

/**
 * The result of the backend validating/executing one proposed action.
 * AiChatService turns it into the HTTP response and updates the stored conversation.
 */
public final class ActionOutcome {
    private final String status;
    private final String reply;
    private final String action;
    private final List<Event> events;
    private final PendingAiAction pending;
    private final List<Slot> suggestions;
    /** null = leave unchanged, "" = forget it (the event was deleted), otherwise the new "last event". */
    private final String lastEventId;

    private ActionOutcome(String status, String reply, String action, List<Event> events,
                          PendingAiAction pending, List<Slot> suggestions, String lastEventId) {
        this.status = status;
        this.reply = reply;
        this.action = action;
        this.events = events == null ? List.of() : events;
        this.pending = pending;
        this.suggestions = suggestions == null ? List.of() : suggestions;
        this.lastEventId = lastEventId;
    }

    public static ActionOutcome info(String reply) {
        return new ActionOutcome(AiStatus.INFO, reply, null, null, null, null, null);
    }

    public static ActionOutcome info(String action, String reply, List<Event> events, List<Slot> suggestions) {
        return new ActionOutcome(AiStatus.INFO, reply, action, events, null, suggestions, null);
    }

    public static ActionOutcome clarification(String question) {
        return new ActionOutcome(AiStatus.NEEDS_CLARIFICATION, question, null, null, null, null, null);
    }

    public static ActionOutcome completed(String action, String reply, List<Event> events, String lastEventId) {
        return new ActionOutcome(AiStatus.COMPLETED, reply, action, events, null, null, lastEventId);
    }

    public static ActionOutcome needsConfirmation(String action, String reply, PendingAiAction pending, List<Slot> suggestions) {
        return new ActionOutcome(AiStatus.NEEDS_CONFIRMATION, reply, action, null, pending, suggestions, null);
    }

    public String getStatus() { return status; }
    public String getReply() { return reply; }
    public String getAction() { return action; }
    public List<Event> getEvents() { return events; }
    public PendingAiAction getPending() { return pending; }
    public List<Slot> getSuggestions() { return suggestions; }
    public String getLastEventId() { return lastEventId; }
}
