package com.hrms.backend.services.aiService;

import com.hrms.backend.dtos.ai.AiHistoryResponse;
import com.hrms.backend.dtos.ai.AiMessageDto;
import com.hrms.backend.dtos.ai.GeminiActionResponse;
import com.hrms.backend.dtos.ai.GeminiChatResponse;
import com.hrms.backend.dtos.ai.PendingActionDto;
import com.hrms.backend.dtos.ai.SuggestionDto;
import com.hrms.backend.dtos.entityDtos.Event.EventResponseDto;
import com.hrms.backend.exceptions.AiServiceException;
import com.hrms.backend.exceptions.BadApiRequestException;
import com.hrms.backend.models.AiConversation;
import com.hrms.backend.models.AiMessage;
import com.hrms.backend.models.Event;
import com.hrms.backend.models.PendingAiAction;
import com.hrms.backend.repositories.AiConversationRepository;
import com.hrms.backend.services.eventService.EventServiceInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * THE ORCHESTRATOR: runs one assistant turn from start to finish.
 *
 *   message -> rate limit -> load THIS user's conversation -> (is the user answering a pending yes/no?)
 *           -> build context from THIS user's events -> Gemini (GeminiService) -> validate/execute
 *           (GeminiActionService) -> save conversation -> response
 *
 * The controller only calls this class with the userId taken from the JWT, so everything below is
 * automatically scoped to one user: the events loaded for the prompt, the conversation, the pending action.
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    static final int MAX_STORED_MESSAGES = 20;     // retention: messages kept per user
    static final int CONTEXT_MESSAGES = 8;         // how many recent messages Gemini sees
    static final int MAX_MESSAGE_CHARS = 1000;
    static final int CONTEXT_DAYS = 30;            // how far ahead the events shown to Gemini reach
    static final int CONTEXT_MAX_EVENTS = 60;
    static final Duration PENDING_TTL = Duration.ofMinutes(10);
    static final Duration CONVERSATION_STALE_AFTER = Duration.ofHours(24);

    private final GeminiService geminiService;
    private final GeminiActionService actionService;
    private final EventServiceInterface eventService;
    private final AiConversationRepository conversationRepository;
    private final AiRateLimiter rateLimiter;
    private final Clock clock;

    @Autowired
    public AiChatService(GeminiService geminiService, GeminiActionService actionService, EventServiceInterface eventService,
                         AiConversationRepository conversationRepository, AiRateLimiter rateLimiter, Clock clock) {
        this.geminiService = geminiService;
        this.actionService = actionService;
        this.eventService = eventService;
        this.conversationRepository = conversationRepository;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    // ------------------------------------------------------------ POST /ai/chat

    public GeminiChatResponse chat(String userId, String message, String timeZone) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty()) throw new BadApiRequestException("Please type a message.");
        if (text.length() > MAX_MESSAGE_CHARS) throw new BadApiRequestException("Your message is too long (maximum " + MAX_MESSAGE_CHARS + " characters).");
        if (!rateLimiter.tryAcquire(userId)) {
            throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "You're sending messages too quickly. Please wait a moment and try again.");
        }

        ZoneId zone = parseZone(timeZone);
        Instant now = clock.instant();
        try {
            AiConversation conv = loadConversation(userId, now);
            PendingAiAction pending = activePending(conv, now);

            // The assistant asked "Should I...?" and the user typed a bare yes/no: no need to call Gemini.
            if (pending != null && AiText.isAffirmative(text)) {
                return resolvePending(conv, userId, zone, now, pending, true, text);
            }
            if (pending != null && AiText.isNegative(text)) {
                return resolvePending(conv, userId, zone, now, pending, false, text);
            }

            // Any other message drops the pending proposal (Gemini is told about it so it can re-propose).
            String pendingSummary = pending == null ? null : pending.getSummary();
            conv.setPendingAction(null);

            AiPromptContext ctx = buildContext(userId, zone, now, conv, pendingSummary);
            GeminiActionResponse proposal = geminiService.interpret(text, ctx);         // Gemini PROPOSES
            ActionOutcome outcome = actionService.handle(userId, zone, now, proposal);   // backend VALIDATES + EXECUTES

            conv = recordTurn(conv, text, outcome, now);
            saveConversation(conv);
            return toResponse(outcome, zone);
        } catch (AiServiceException | BadApiRequestException e) {
            throw e;
        } catch (RuntimeException e) {
            // Database failure or a bug: log the details for the developer, show the user a safe message.
            log.error("Unexpected error while handling an AI chat message", e);
            throw new AiServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong while processing your request. Please try again.");
        }
    }

    // ------------------------------------------------------------ POST /ai/confirm

    public GeminiChatResponse confirm(String userId, String pendingActionId, boolean confirm, String timeZone) {
        ZoneId zone = parseZone(timeZone);
        Instant now = clock.instant();
        try {
            AiConversation conv = loadConversation(userId, now);
            PendingAiAction pending = activePending(conv, now);
            // The client only names an id; WHAT will happen is whatever the server stored when it proposed it.
            if (pending == null || pendingActionId == null || !pendingActionId.equals(pending.getId())) {
                throw new AiServiceException(HttpStatus.CONFLICT, "There is nothing waiting for your confirmation. It may have expired.");
            }
            return resolvePending(conv, userId, zone, now, pending, confirm, confirm ? "Yes, go ahead" : "No, don't");
        } catch (AiServiceException | BadApiRequestException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Unexpected error while confirming an AI action", e);
            throw new AiServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong while processing your request. Please try again.");
        }
    }

    // ------------------------------------------------------------ GET /ai/history, DELETE /ai/conversation

    public AiHistoryResponse history(String userId) {
        Instant now = clock.instant();
        AiConversation conv = conversationRepository.findByUserId(userId).orElse(null);
        if (conv == null || isStale(conv, now)) return new AiHistoryResponse(List.of(), null);
        List<AiMessageDto> messages = new ArrayList<>();
        if (conv.getMessages() != null) {
            for (AiMessage m : conv.getMessages()) {
                messages.add(new AiMessageDto(m.getRole(), m.getText(), m.getTimestamp() == null ? null : m.getTimestamp().toString()));
            }
        }
        PendingAiAction pending = activePending(conv, now);
        return new AiHistoryResponse(messages, pending == null ? null : new PendingActionDto(pending.getId(), pending.getType(), pending.getSummary()));
    }

    public void clearConversation(String userId) {
        conversationRepository.deleteByUserId(userId);
    }

    // ------------------------------------------------------------ internals

    private GeminiChatResponse resolvePending(AiConversation conv, String userId, ZoneId zone, Instant now,
                                              PendingAiAction pending, boolean confirm, String userText) {
        // CLAIM the pending action BEFORE executing it. If the user double-taps "Confirm", the second request
        // loads the same old version of the conversation and its save fails (optimistic lock), so the
        // action can never run twice.
        conv.setPendingAction(null);
        appendMessage(conv, "USER", userText, now);
        conv = saveConversation(conv);

        ActionOutcome outcome = confirm
                ? actionService.executePending(userId, zone, now, pending)
                : ActionOutcome.info("Okay, I won't do that.");

        applyOutcome(conv, outcome);
        appendMessage(conv, "ASSISTANT", outcome.getReply(), now);
        trim(conv);
        saveConversation(conv);
        return toResponse(outcome, zone);
    }

    private AiPromptContext buildContext(String userId, ZoneId zone, Instant now, AiConversation conv, String pendingSummary) {
        Instant from = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        Instant to = from.plus(Duration.ofDays(CONTEXT_DAYS));
        List<Event> events = eventService.findBetween(userId, from, to); // ONLY this user's events
        if (events.size() > CONTEXT_MAX_EVENTS) events = events.subList(0, CONTEXT_MAX_EVENTS);

        List<AiMessage> all = conv.getMessages() == null ? List.of() : conv.getMessages();
        List<AiMessage> recent = new ArrayList<>(all.subList(Math.max(0, all.size() - CONTEXT_MESSAGES), all.size()));
        return new AiPromptContext(zone, now, events, recent, pendingSummary, conv.getLastEventId());
    }

    private AiConversation loadConversation(String userId, Instant now) {
        AiConversation conv = conversationRepository.findByUserId(userId).orElseGet(() -> {
            AiConversation c = new AiConversation();
            c.setUserId(userId);
            return c;
        });
        if (isStale(conv, now)) {
            // Idle for a long time: start fresh (retention limit).
            conv.setMessages(new ArrayList<>());
            conv.setPendingAction(null);
            conv.setLastEventId(null);
        }
        if (conv.getMessages() == null) conv.setMessages(new ArrayList<>());
        return conv;
    }

    private static boolean isStale(AiConversation conv, Instant now) {
        return conv.getUpdatedAt() != null && conv.getUpdatedAt().isBefore(now.minus(CONVERSATION_STALE_AFTER));
    }

    /** The pending action if it exists and has not expired; an expired one is dropped from the in-memory copy. */
    private static PendingAiAction activePending(AiConversation conv, Instant now) {
        PendingAiAction p = conv.getPendingAction();
        if (p == null) return null;
        if (p.getCreatedAt() != null && p.getCreatedAt().plus(PENDING_TTL).isBefore(now)) {
            conv.setPendingAction(null);
            return null;
        }
        return p;
    }

    private AiConversation recordTurn(AiConversation conv, String userText, ActionOutcome outcome, Instant now) {
        appendMessage(conv, "USER", userText, now);
        appendMessage(conv, "ASSISTANT", outcome.getReply(), now);
        conv.setPendingAction(outcome.getPending());
        applyOutcome(conv, outcome);
        trim(conv);
        return conv;
    }

    private static void applyOutcome(AiConversation conv, ActionOutcome outcome) {
        String last = outcome.getLastEventId();
        if (last != null) conv.setLastEventId(last.isEmpty() ? null : last);
        if (outcome.getPending() != null) conv.setPendingAction(outcome.getPending());
    }

    private static void appendMessage(AiConversation conv, String role, String text, Instant now) {
        String t = text == null ? "" : text;
        if (t.length() > 2000) t = t.substring(0, 2000);
        conv.getMessages().add(new AiMessage(role, t, now));
    }

    private static void trim(AiConversation conv) {
        while (conv.getMessages().size() > MAX_STORED_MESSAGES) conv.getMessages().remove(0);
    }

    private AiConversation saveConversation(AiConversation conv) {
        conv.setUpdatedAt(clock.instant());
        try {
            return conversationRepository.save(conv);
        } catch (OptimisticLockingFailureException | DuplicateKeyException e) {
            throw new AiServiceException(HttpStatus.CONFLICT, "Your previous request is still being processed. Please try again in a moment.");
        }
    }

    private static ZoneId parseZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(timeZone.trim());
        } catch (DateTimeException e) {
            return ZoneId.of("UTC");
        }
    }

    private static GeminiChatResponse toResponse(ActionOutcome outcome, ZoneId zone) {
        List<EventResponseDto> events = new ArrayList<>();
        for (Event e : outcome.getEvents()) events.add(EventResponseDto.from(e));
        List<SuggestionDto> suggestions = new ArrayList<>();
        for (Slot s : outcome.getSuggestions()) {
            suggestions.add(new SuggestionDto(s.start().toString(), s.end().toString(), GeminiActionService.slotLabel(s, zone)));
        }
        PendingAiAction p = outcome.getPending();
        PendingActionDto pending = p == null ? null : new PendingActionDto(p.getId(), p.getType(), p.getSummary());
        return new GeminiChatResponse(outcome.getStatus(), outcome.getReply(), outcome.getAction(), events, pending, suggestions);
    }
}
