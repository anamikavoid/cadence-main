package com.hrms.backend.services.aiService;

import com.hrms.backend.dtos.ai.AiHistoryResponse;
import com.hrms.backend.dtos.ai.AiStatus;
import com.hrms.backend.dtos.ai.GeminiChatResponse;
import com.hrms.backend.exceptions.AiServiceException;
import com.hrms.backend.exceptions.BadApiRequestException;
import com.hrms.backend.models.Event;
import com.hrms.backend.models.User;
import com.hrms.backend.utils.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Duration;
import java.time.Instant;

import static com.hrms.backend.services.aiService.AiTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole assistant turns from message to response, with a scripted fake Gemini.
 * "now" is Saturday 21 Nov 2026 14:00 India time, so "tomorrow" is Sunday 22 Nov.
 */
class AiChatServiceTest {
    private AiTestSupport.Fixture f;

    @BeforeEach
    void setUp() {
        f = AiTestSupport.Fixture.create();
    }

    private GeminiChatResponse chat(String user, String message) {
        return f.chat.chat(user, message, TZ);
    }

    private String createDsaJson() {
        return json("action", "CREATE_EVENT", "title", "DSA Assignment", "date", "2026-11-22", "startTime", "17:00",
                "category", "ASSIGNMENT", "priority", "HIGH");
    }

    // ------------------------------------------------------------------ the main user stories

    @Test
    void naturalLanguageCreationEndToEnd() {
        f.gemini.enqueue(createDsaJson());
        GeminiChatResponse r = chat("alice", "Remind me to complete my DSA assignment tomorrow at 5 PM.");
        assertEquals(AiStatus.COMPLETED, r.status());
        assertEquals("CREATE_EVENT", r.action());
        assertEquals(1, r.events().size());
        assertEquals("ASSIGNMENT", r.events().get(0).category());
        assertEquals("HIGH", r.events().get(0).priority());
        assertEquals(ist("2026-11-22", "17:00").toString(), r.events().get(0).startTime());
        assertEquals(1, countEvents(f, "alice"));
        assertTrue(f.gemini.lastContent().contains("Remind me to complete my DSA assignment tomorrow at 5 PM."));
        assertTrue(f.gemini.lastContent().contains("2026-11-22 Sunday (tomorrow)"));
        AiHistoryResponse h = f.chat.history("alice");
        assertEquals(2, h.messages().size());
        assertEquals("USER", h.messages().get(0).role());
        assertEquals("ASSISTANT", h.messages().get(1).role());
    }

    @Test
    void followUpMoveItUsesTheEventJustCreated() {
        f.gemini.enqueue(createDsaJson());
        String id = chat("alice", "Schedule a DSA assignment tomorrow at 5 PM").events().get(0).id();

        f.gemini.enqueue(json("action", "UPDATE_EVENT", "targetEventId", id, "startTime", "19:00"));
        GeminiChatResponse r = chat("alice", "Move it to 7 PM");
        assertEquals(AiStatus.COMPLETED, r.status());
        assertEquals(ist("2026-11-22", "19:00"), f.eventService.getById("alice", id).getStartTime());
        // Gemini was told what "it" refers to and saw the earlier messages.
        assertTrue(f.gemini.lastContent().contains("LAST EVENT"));
        assertTrue(f.gemini.lastContent().contains("id=" + id));
        assertTrue(f.gemini.lastContent().contains("Schedule a DSA assignment tomorrow at 5 PM"));
    }

    @Test
    void incompleteRequestsGetAQuestionAndTheAnswerCompletesThem() {
        f.gemini.enqueue(json("action", "ASK_CLARIFICATION", "reply", "What time should I schedule the meeting?"));
        GeminiChatResponse q = chat("alice", "Schedule a meeting tomorrow.");
        assertEquals(AiStatus.NEEDS_CLARIFICATION, q.status());
        assertEquals("What time should I schedule the meeting?", q.reply());
        assertEquals(0, countEvents(f, "alice"));

        f.gemini.enqueue(json("action", "CREATE_EVENT", "title", "Meeting", "date", "2026-11-22", "startTime", "10:00"));
        GeminiChatResponse done = chat("alice", "10 AM");
        assertEquals(AiStatus.COMPLETED, done.status());
        assertTrue(f.gemini.lastContent().contains("Assistant: What time should I schedule the meeting?"), "the follow-up sees the question");
    }

    @Test
    void ifGeminiForgetsToAskTheBackendStillAsks() {
        f.gemini.enqueue(json("action", "CREATE_EVENT", "title", "Meeting", "date", "2026-11-22")); // no start time
        GeminiChatResponse r = chat("alice", "Schedule a meeting tomorrow");
        assertEquals(AiStatus.NEEDS_CLARIFICATION, r.status());
        assertEquals(0, countEvents(f, "alice"));
    }

    @Test
    void scheduleQuestionsAreAnsweredFromTheDatabaseNotFromTheModel() {
        addEvent(f, "alice", "Maths", "2026-11-22", "10:00", "11:00");
        // Gemini "hallucinates" in reply, but QUERY_EVENTS replies are produced by the backend.
        f.gemini.enqueue(json("action", "QUERY_EVENTS", "queryMode", "LIST", "queryFromDate", "2026-11-22", "queryToDate", "2026-11-22",
                "reply", "You have a dentist appointment at 3 PM"));
        GeminiChatResponse r = chat("alice", "What do I have tomorrow?");
        assertTrue(r.reply().contains("Maths"));
        assertFalse(r.reply().contains("dentist"));
    }

    // ------------------------------------------------------------------ confirmation flow

    private GeminiChatResponse conflictProposal() {
        addEvent(f, "alice", "Maths", "2026-11-22", "17:00", "18:00");
        f.gemini.enqueue(json("action", "CREATE_EVENT", "title", "DSA", "date", "2026-11-22", "startTime", "17:30"));
        return chat("alice", "Schedule DSA tomorrow at 5:30 PM");
    }

    @Test
    void aConflictIsProposedThenAYesInTextConfirmsWithoutCallingGeminiAgain() {
        GeminiChatResponse p = conflictProposal();
        assertEquals(AiStatus.NEEDS_CONFIRMATION, p.status());
        assertNotNull(p.pendingAction());
        assertFalse(p.suggestions().isEmpty());
        assertEquals(1, countEvents(f, "alice"));
        assertEquals(1, f.gemini.calls);

        GeminiChatResponse yes = chat("alice", "yes");
        assertEquals(AiStatus.COMPLETED, yes.status());
        assertEquals(2, countEvents(f, "alice"));
        assertEquals(1, f.gemini.calls, "a bare yes must not cost a Gemini call");
    }

    @Test
    void aNoDeclinesTheProposal() {
        conflictProposal();
        GeminiChatResponse no = chat("alice", "No.");
        assertEquals(AiStatus.INFO, no.status());
        assertEquals(1, countEvents(f, "alice"));
        assertNull(f.chat.history("alice").pendingAction());
    }

    @Test
    void anyOtherMessageDropsThePendingProposal() {
        conflictProposal();
        f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "Sure."));
        chat("alice", "actually, what's the weather like?");
        assertNull(f.chat.history("alice").pendingAction());
        assertTrue(f.gemini.lastContent().contains("PENDING PROPOSAL"), "Gemini is told so it can re-propose");
        f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "What would you like me to confirm?"));
        chat("alice", "yes");
        assertEquals(1, countEvents(f, "alice"), "a late yes must not execute the dropped proposal");
    }

    @Test
    void cancelNeedsConfirmationButtonsWorkAndCannotRunTwice() {
        Event e = addEvent(f, "alice", "Gym", "2026-11-22", "07:00", "08:00");
        f.gemini.enqueue(json("action", "CANCEL_EVENT", "targetEventId", e.getId()));
        GeminiChatResponse p = chat("alice", "Cancel my gym session tomorrow");
        assertEquals(AiStatus.NEEDS_CONFIRMATION, p.status());
        assertEquals(1, countEvents(f, "alice"));

        AiServiceException wrongId = assertThrows(AiServiceException.class, () -> f.chat.confirm("alice", "not-the-id", true, TZ));
        assertEquals(HttpStatus.CONFLICT, wrongId.getStatus());
        assertEquals(1, countEvents(f, "alice"));

        GeminiChatResponse done = f.chat.confirm("alice", p.pendingAction().id(), true, TZ);
        assertEquals(AiStatus.COMPLETED, done.status());
        assertEquals(0, countEvents(f, "alice"));

        AiServiceException again = assertThrows(AiServiceException.class, () -> f.chat.confirm("alice", p.pendingAction().id(), true, TZ));
        assertEquals(HttpStatus.CONFLICT, again.getStatus(), "a double tap must not run it again");
    }

    @Test
    void theCancelButtonKeepsTheEvent() {
        Event e = addEvent(f, "alice", "Gym", "2026-11-22", "07:00", "08:00");
        f.gemini.enqueue(json("action", "CANCEL_EVENT", "targetEventId", e.getId()));
        GeminiChatResponse p = chat("alice", "Delete my gym reminder");
        GeminiChatResponse r = f.chat.confirm("alice", p.pendingAction().id(), false, TZ);
        assertEquals(AiStatus.INFO, r.status());
        assertEquals(1, countEvents(f, "alice"));
    }

    @Test
    void aTextYesConfirmsACancelAndTheConversationForgetsTheDeletedEvent() {
        Event e = addEvent(f, "alice", "Gym", "2026-11-22", "07:00", "08:00");
        f.gemini.enqueue(json("action", "CANCEL_EVENT", "targetEventId", e.getId()));
        chat("alice", "Cancel my gym session");
        assertEquals(AiStatus.COMPLETED, chat("alice", "Yes").status());
        assertEquals(0, countEvents(f, "alice"));
        f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "ok"));
        chat("alice", "thanks");
        assertFalse(f.gemini.lastContent().contains("LAST EVENT"));
    }

    @Test
    void aProposalExpiresAfterTenMinutes() {
        GeminiChatResponse p = conflictProposal();
        f.clock.advance(Duration.ofMinutes(11));
        f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "Hi"));
        chat("alice", "yes");
        assertEquals(1, countEvents(f, "alice"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(AiServiceException.class,
                () -> f.chat.confirm("alice", p.pendingAction().id(), true, TZ)).getStatus());
    }

    // ------------------------------------------------------------------ users must stay separate

    @Test
    void usersNeverSeeEachOthersEventsNotEvenInThePromptSentToGemini() {
        f.gemini.enqueue(json("action", "CREATE_EVENT", "title", "Alice Secret Meeting", "date", "2026-11-22", "startTime", "10:00"));
        chat("alice", "Schedule my secret meeting tomorrow at 10");

        f.gemini.enqueue(json("action", "QUERY_EVENTS", "queryMode", "LIST", "queryFromDate", "2026-11-22", "queryToDate", "2026-11-22"));
        GeminiChatResponse bob = chat("bob", "What do I have tomorrow?");
        assertEquals("You have nothing scheduled for tomorrow.", bob.reply());
        assertFalse(f.gemini.lastContent().contains("Alice Secret Meeting"), "Bob's prompt must not contain Alice's data");

        f.gemini.enqueue(json("action", "QUERY_EVENTS", "queryMode", "LIST", "queryFromDate", "2026-11-22", "queryToDate", "2026-11-22"));
        GeminiChatResponse alice = chat("alice", "What do I have tomorrow?");
        assertTrue(f.gemini.lastContent().contains("Alice Secret Meeting"));
        assertTrue(alice.reply().contains("Alice Secret Meeting"));
    }

    @Test
    void geminiCannotBeUsedToTouchAnotherUsersEvent() {
        Event alices = addEvent(f, "alice", "Alice dentist", "2026-11-22", "10:00", "11:00");
        f.gemini.enqueue(json("action", "CANCEL_EVENT", "targetEventId", alices.getId()));
        GeminiChatResponse r = chat("bob", "cancel that event");
        assertEquals(AiStatus.NEEDS_CLARIFICATION, r.status());
        assertNull(r.pendingAction());

        f.gemini.enqueue(json("action", "UPDATE_EVENT", "targetEventId", alices.getId(), "startTime", "20:00"));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, chat("bob", "move that event to 8 PM").status());
        assertEquals(ist("2026-11-22", "10:00"), f.eventService.getById("alice", alices.getId()).getStartTime());
    }

    @Test
    void aUserCannotConfirmAnotherUsersPendingAction() {
        Event e = addEvent(f, "alice", "Gym", "2026-11-22", "07:00", "08:00");
        f.gemini.enqueue(json("action", "CANCEL_EVENT", "targetEventId", e.getId()));
        GeminiChatResponse p = chat("alice", "cancel gym");
        AiServiceException ex = assertThrows(AiServiceException.class, () -> f.chat.confirm("bob", p.pendingAction().id(), true, TZ));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(1, countEvents(f, "alice"));
        assertNotNull(f.chat.history("alice").pendingAction(), "Alice's proposal is untouched");
        assertTrue(f.chat.history("bob").messages().isEmpty());
    }

    @Test
    void aBareYesFromAnotherUserDoesNotConfirmSomeoneElsesProposal() {
        Event e = addEvent(f, "alice", "Gym", "2026-11-22", "07:00", "08:00");
        f.gemini.enqueue(json("action", "CANCEL_EVENT", "targetEventId", e.getId()));
        chat("alice", "cancel gym");
        f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "Hi"));
        chat("bob", "yes");
        assertEquals(1, countEvents(f, "alice"));
    }

    @Test
    void historyAndClearOnlyAffectTheCaller() {
        f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "Hello alice"));
        chat("alice", "hi");
        f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "Hello bob"));
        chat("bob", "hi");
        f.chat.clearConversation("alice");
        assertTrue(f.chat.history("alice").messages().isEmpty());
        assertEquals(2, f.chat.history("bob").messages().size());
    }

    // ------------------------------------------------------------------ failures

    @Test
    void aGeminiOutageChangesNothingAndStoresNothing() {
        f.gemini.enqueueError(new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "The AI assistant is busy right now. Please try again in a minute."));
        AiServiceException e = assertThrows(AiServiceException.class, () -> chat("alice", "Schedule gym tomorrow at 7 AM"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        assertEquals(0, countEvents(f, "alice"));
        assertTrue(f.chat.history("alice").messages().isEmpty());
    }

    @Test
    void aGarbageAnswerFromGeminiIsAFriendlyErrorAndNeverCrashesOrChangesData() {
        f.gemini.enqueue("<html>502 Bad Gateway</html>");
        f.gemini.enqueue("still not json");
        AiServiceException e = assertThrows(AiServiceException.class, () -> chat("alice", "Schedule gym tomorrow at 7 AM"));
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
        assertFalse(e.getMessage().contains("html"));
        assertEquals(0, countEvents(f, "alice"));
    }

    @Test
    void aDatabaseFailureIsHiddenBehindASafeMessage() {
        AiTestSupport.Fixture broken = AiTestSupport.Fixture.withBrokenDatabase();
        AiServiceException e = assertThrows(AiServiceException.class, () -> broken.chat.chat("alice", "hi", TZ));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatus());
        assertFalse(e.getMessage().contains("boom"));
        assertFalse(e.getMessage().contains("secret"));
        assertFalse(e.getMessage().contains("mongodb"));
    }

    @Test
    void tooManyRequestsAreRateLimited() {
        AiTestSupport.Fixture limited = AiTestSupport.Fixture.withRateLimit(2);
        limited.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "1"));
        limited.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "2"));
        limited.chat.chat("alice", "a", TZ);
        limited.chat.chat("alice", "b", TZ);
        AiServiceException e = assertThrows(AiServiceException.class, () -> limited.chat.chat("alice", "c", TZ));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatus());
        assertEquals(2, limited.gemini.calls, "the blocked request never reached Gemini");
        limited.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "3"));
        limited.chat.chat("bob", "hi", TZ); // another user is not affected
    }

    @Test
    void emptyAndOversizedMessagesAreRejectedBeforeGemini() {
        assertThrows(BadApiRequestException.class, () -> chat("alice", "   "));
        assertThrows(BadApiRequestException.class, () -> chat("alice", null));
        assertThrows(BadApiRequestException.class, () -> chat("alice", "x".repeat(1001)));
        assertEquals(0, f.gemini.calls);
    }

    // ------------------------------------------------------------------ retention, time zones, identity

    @Test
    void storedHistoryAndTheContextSentToGeminiHaveFixedLimits() {
        for (int i = 0; i < 15; i++) {
            f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "reply " + i));
            chat("alice", "message " + i);
        }
        assertEquals(AiChatService.MAX_STORED_MESSAGES, f.chat.history("alice").messages().size());
        long contextLines = f.gemini.lastContent().lines().filter(l -> l.startsWith("User: ") || l.startsWith("Assistant: ")).count();
        assertEquals(AiChatService.CONTEXT_MESSAGES, contextLines);
    }

    @Test
    void anIdleConversationIsResetAfter24Hours() {
        f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "Hello"));
        chat("alice", "hi there");
        f.clock.advance(Duration.ofHours(25));
        f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "Hello again"));
        chat("alice", "hello again");
        assertTrue(f.gemini.lastContent().contains("RECENT CONVERSATION (oldest first):\n(none)"));
        assertEquals(2, f.chat.history("alice").messages().size());
    }

    @Test
    void aStaleHistoryIsNotReturnedEither() {
        f.gemini.enqueue(json("action", "GENERAL_RESPONSE", "reply", "Hello"));
        chat("alice", "hi");
        f.clock.advance(Duration.ofHours(25));
        assertTrue(f.chat.history("alice").messages().isEmpty());
    }

    @Test
    void timesAreInterpretedInTheUsersTimeZone() {
        f.gemini.enqueue(createDsaJson());
        Event ny = f.chat.chat("alice", "5 PM tomorrow", "America/New_York").events().isEmpty() ? null
                : f.eventService.findBetween("alice", Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-11-30T00:00:00Z")).get(0);
        assertNotNull(ny);
        assertEquals(Instant.parse("2026-11-22T22:00:00Z"), ny.getStartTime(), "17:00 New York (EST) is 22:00 UTC");
    }

    @Test
    void anInvalidTimeZoneFallsBackToUtcInsteadOfFailing() {
        f.gemini.enqueue(createDsaJson());
        f.chat.chat("alice", "5 PM tomorrow", "Mars/Phobos");
        Event e = f.eventService.findBetween("alice", Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-11-30T00:00:00Z")).get(0);
        assertEquals(Instant.parse("2026-11-22T17:00:00Z"), e.getStartTime());
    }

    @Test
    void theUserIdOnlyEverComesFromTheAuthenticatedPrincipal() {
        User u = new User();
        u.setId("user-123");
        assertEquals("user-123", CurrentUser.id(new UsernamePasswordAuthenticationToken(u, null)));
        assertThrows(AccessDeniedException.class, () -> CurrentUser.id(null));
        assertThrows(AccessDeniedException.class, () -> CurrentUser.id(new UsernamePasswordAuthenticationToken("anonymousUser", null)));
        assertThrows(AccessDeniedException.class, () -> CurrentUser.id(new UsernamePasswordAuthenticationToken(new User(), null)));
    }
}
