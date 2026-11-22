package com.hrms.backend.services.aiService;

import com.hrms.backend.dtos.ai.AiStatus;
import com.hrms.backend.dtos.ai.GeminiActionResponse;
import com.hrms.backend.dtos.entityDtos.Event.EventRequestDto;
import com.hrms.backend.exceptions.AiServiceException;
import com.hrms.backend.models.Event;
import com.hrms.backend.models.PendingAiAction;
import com.hrms.backend.models.enums.EventCategory;
import com.hrms.backend.models.enums.EventPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.hrms.backend.services.aiService.AiTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The safety layer between Gemini and the database: every proposed action is validated and
 * executed (or refused) here. These tests call it directly with hand-made "Gemini proposals".
 * Times: "now" is Saturday 21 Nov 2026 14:00 India time; "tomorrow" is Sunday 22 Nov.
 */
class GeminiActionServiceTest {
    private static final String U = "u1";
    private AiTestSupport.Fixture f;

    @BeforeEach
    void setUp() {
        f = AiTestSupport.Fixture.create();
    }

    private ActionOutcome handle(GeminiActionResponse a) {
        return f.actionService.handle(U, IST, NOW, a);
    }

    // ------------------------------------------------------------------ CREATE

    @Test
    void createsAnEventAndAssumesOneHourWhenNoDurationIsGiven() {
        ActionOutcome o = handle(action("CREATE_EVENT", "title", "DSA Assignment", "date", "2026-11-22", "startTime", "17:00"));
        assertEquals(AiStatus.COMPLETED, o.getStatus());
        Event e = o.getEvents().get(0);
        assertEquals(U, e.getUserId());
        assertEquals("AI", e.getSource());
        assertEquals(ist("2026-11-22", "17:00"), e.getStartTime());
        assertEquals(ist("2026-11-22", "18:00"), e.getEndTime());
        assertTrue(o.getReply().contains("Sun, Nov 22, 5:00 PM \u2013 6:00 PM"), o.getReply());
        assertTrue(o.getReply().contains("I assumed it lasts 1 hour"));
        assertEquals(e.getId(), o.getLastEventId());
        assertEquals(1, countEvents(f, U));
    }

    @Test
    void anExplicitEndTimeOrDurationIsUsedAndNotAssumed() {
        ActionOutcome a = handle(action("CREATE_EVENT", "title", "Lab", "date", "2026-11-23", "startTime", "11:00", "endTime", "13:00"));
        assertEquals(ist("2026-11-23", "13:00"), a.getEvents().get(0).getEndTime());
        assertFalse(a.getReply().contains("assumed"));
        ActionOutcome b = handle(action("CREATE_EVENT", "title", "Study", "date", "2026-11-24", "startTime", "11:00", "durationMinutes", "90"));
        assertEquals(ist("2026-11-24", "12:30"), b.getEvents().get(0).getEndTime());
    }

    @Test
    void categoryPriorityLocationAndDescriptionAreSaved() {
        ActionOutcome o = handle(action("CREATE_EVENT", "title", "Maths", "date", "2026-11-22", "startTime", "10:00",
                "category", "class", "priority", "high", "location", "Room 5", "description", "Chapter 3"));
        Event e = o.getEvents().get(0);
        assertEquals(EventCategory.CLASS, e.getCategory());
        assertEquals(EventPriority.HIGH, e.getPriority());
        assertEquals("Room 5", e.getLocation());
        assertEquals("Chapter 3", e.getDescription());
    }

    @Test
    void unknownCategoryAndPriorityValuesFallBackToDefaultsInsteadOfFailing() {
        Event e = handle(action("CREATE_EVENT", "title", "X", "date", "2026-11-22", "startTime", "10:00", "category", "MAGIC", "priority", "URGENT!!")).getEvents().get(0);
        assertEquals(EventCategory.OTHER, e.getCategory());
        assertEquals(EventPriority.MEDIUM, e.getPriority());
    }

    @Test
    void aMissingTimeIsAskedForNeverInvented() {
        ActionOutcome o = handle(action("CREATE_EVENT", "title", "Meeting", "date", "2026-11-22"));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, o.getStatus());
        assertTrue(o.getReply().contains("What time"), o.getReply());
        assertEquals(0, countEvents(f, U));
    }

    @Test
    void aMissingDateOrTitleIsAskedFor() {
        ActionOutcome noDate = handle(action("CREATE_EVENT", "title", "Meeting", "startTime", "10:00"));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, noDate.getStatus());
        assertTrue(noDate.getReply().contains("Which date"));
        ActionOutcome noTitle = handle(action("CREATE_EVENT", "date", "2026-11-22", "startTime", "10:00"));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, noTitle.getStatus());
        assertTrue(noTitle.getReply().contains("What should I call"));
        assertEquals(0, countEvents(f, U));
    }

    @Test
    void malformedDatesAndTimesAreAskedAgainInsteadOfCrashing() {
        for (String[] bad : new String[][]{{"tomorrow", "17:00"}, {"2026-13-45", "17:00"}, {"2026-11-22", "25:99"}, {"2026-11-22", "five pm"}}) {
            ActionOutcome o = handle(action("CREATE_EVENT", "title", "X", "date", bad[0], "startTime", bad[1]));
            assertEquals(AiStatus.NEEDS_CLARIFICATION, o.getStatus(), bad[0] + " " + bad[1]);
        }
        assertEquals(0, countEvents(f, U));
    }

    @Test
    void aTimeThatAlreadyPassedIsRefusedButLaterTodayIsFine() {
        ActionOutcome past = handle(action("CREATE_EVENT", "title", "X", "date", "2026-11-21", "startTime", "09:00"));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, past.getStatus());
        assertTrue(past.getReply().contains("already passed"));
        assertEquals(AiStatus.COMPLETED, handle(action("CREATE_EVENT", "title", "Later", "date", "2026-11-21", "startTime", "18:00")).getStatus());
    }

    @Test
    void impossibleEndTimesDurationsAndFarFutureDatesAreRefused() {
        assertEquals(AiStatus.NEEDS_CLARIFICATION, handle(action("CREATE_EVENT", "title", "X", "date", "2026-11-22", "startTime", "17:00", "endTime", "16:00")).getStatus());
        assertEquals(AiStatus.NEEDS_CLARIFICATION, handle(action("CREATE_EVENT", "title", "X", "date", "2026-11-22", "startTime", "17:00", "durationMinutes", "5000")).getStatus());
        assertEquals(AiStatus.NEEDS_CLARIFICATION, handle(action("CREATE_EVENT", "title", "X", "date", "2026-11-22", "startTime", "17:00", "durationMinutes", "0")).getStatus());
        assertEquals(AiStatus.NEEDS_CLARIFICATION, handle(action("CREATE_EVENT", "title", "X", "date", "2040-01-01", "startTime", "17:00")).getStatus());
        assertEquals(0, countEvents(f, U));
    }

    // ------------------------------------------------------------------ CONFLICTS

    @Test
    void aConflictIsNeverSavedSilentlyItBecomesAProposalWithAlternatives() {
        addEvent(f, U, "Maths", "2026-11-22", "17:00", "18:00");
        ActionOutcome o = handle(action("CREATE_EVENT", "title", "DSA", "date", "2026-11-22", "startTime", "17:30", "durationMinutes", "30"));
        assertEquals(AiStatus.NEEDS_CONFIRMATION, o.getStatus());
        assertNotNull(o.getPending());
        assertEquals("CREATE_EVENT", o.getPending().getType());
        assertEquals(1, countEvents(f, U), "nothing saved yet");
        assertTrue(o.getReply().contains("overlaps with \"Maths\""), o.getReply());
        assertFalse(o.getSuggestions().isEmpty());
        assertTrue(o.getSuggestions().size() <= 3);
        for (Slot s : o.getSuggestions()) {
            assertTrue(f.conflictService.findConflicts(U, s.start(), s.end(), null).isEmpty(), "a suggestion overlaps an event");
        }
    }

    @Test
    void confirmingAConflictingCreateSavesIt() {
        addEvent(f, U, "Maths", "2026-11-22", "17:00", "18:00");
        ActionOutcome proposal = handle(action("CREATE_EVENT", "title", "DSA", "date", "2026-11-22", "startTime", "17:30"));
        ActionOutcome done = f.actionService.executePending(U, IST, NOW, proposal.getPending());
        assertEquals(AiStatus.COMPLETED, done.getStatus());
        assertEquals(2, countEvents(f, U));
    }

    @Test
    void otherUsersEventsDoNotCauseConflicts() {
        addEvent(f, "u2", "Bob's lecture", "2026-11-22", "17:00", "18:00");
        assertEquals(AiStatus.COMPLETED, handle(action("CREATE_EVENT", "title", "DSA", "date", "2026-11-22", "startTime", "17:00")).getStatus());
    }

    // ------------------------------------------------------------------ pass-through actions

    @Test
    void clarificationAndGeneralResponsesAreReturnedAsTheyAre() {
        ActionOutcome q = handle(action("ASK_CLARIFICATION", "reply", "What time should I schedule the meeting?"));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, q.getStatus());
        assertEquals("What time should I schedule the meeting?", q.getReply());
        ActionOutcome g = handle(action("GENERAL_RESPONSE", "reply", "Hello!"));
        assertEquals(AiStatus.INFO, g.getStatus());
        assertEquals("Hello!", g.getReply());
        assertFalse(handle(action("GENERAL_RESPONSE")).getReply().isEmpty(), "empty replies get a default");
        assertFalse(handle(action("ASK_CLARIFICATION")).getReply().isEmpty());
    }

    @Test
    void anUnknownActionIsAnError() {
        assertThrows(AiServiceException.class, () -> handle(action("FORMAT_DISK")));
    }

    // ------------------------------------------------------------------ QUERY

    @Test
    void listingShowsOnlyTheUsersRealEventsInTimeOrder() {
        addEvent(f, U, "Maths", "2026-11-22", "10:00", "11:00");
        addEvent(f, U, "Gym", "2026-11-22", "07:00", "08:00");
        addEvent(f, "u2", "Someone else's secret", "2026-11-22", "09:00", "10:00");
        ActionOutcome o = handle(action("QUERY_EVENTS", "queryMode", "LIST", "queryFromDate", "2026-11-22", "queryToDate", "2026-11-22"));
        assertEquals(AiStatus.INFO, o.getStatus());
        assertTrue(o.getReply().startsWith("Here's your schedule for tomorrow:"), o.getReply());
        assertTrue(o.getReply().contains("Gym") && o.getReply().contains("Maths"));
        assertTrue(o.getReply().indexOf("Gym") < o.getReply().indexOf("Maths"), "earliest first");
        assertFalse(o.getReply().contains("secret"));
        assertEquals(2, o.getEvents().size());
    }

    @Test
    void anEmptyDayIsReportedHonestly() {
        ActionOutcome o = handle(action("QUERY_EVENTS", "queryMode", "LIST", "queryFromDate", "2026-11-22", "queryToDate", "2026-11-22"));
        assertEquals("You have nothing scheduled for tomorrow.", o.getReply());
        assertTrue(o.getEvents().isEmpty());
    }

    @Test
    void multiDayListingsGroupByDayAndSkipEventsThatAlreadyFinished() {
        addEvent(f, U, "Breakfast", "2026-11-21", "09:00", "10:00");   // finished before "now" (14:00)
        addEvent(f, U, "Gym", "2026-11-21", "16:00", "17:00");
        addEvent(f, U, "Maths", "2026-11-22", "10:00", "11:00");
        ActionOutcome o = handle(action("QUERY_EVENTS", "queryMode", "LIST", "queryFromDate", "2026-11-21", "queryToDate", "2026-11-23"));
        assertFalse(o.getReply().contains("Breakfast"));
        assertTrue(o.getReply().contains("Sat, Nov 21:") && o.getReply().contains("Sun, Nov 22:"), o.getReply());
        assertTrue(o.getReply().contains("Gym") && o.getReply().contains("Maths"));
        // A single-day question about today still shows the whole day.
        ActionOutcome today = handle(action("QUERY_EVENTS", "queryMode", "LIST", "queryFromDate", "2026-11-21", "queryToDate", "2026-11-21"));
        assertTrue(today.getReply().contains("Breakfast"));
    }

    @Test
    void keywordAndCategoryFiltersWork() {
        addEvent(f, U, "Gym", "2026-11-22", "07:00", "08:00");
        EventRequestDto d = new EventRequestDto();
        d.setTitle("DSA homework");
        d.setCategory(EventCategory.ASSIGNMENT);
        d.setStartTime(ist("2026-11-22", "18:00"));
        f.eventService.create(U, d, "MANUAL");
        ActionOutcome byKeyword = handle(action("QUERY_EVENTS", "queryFromDate", "2026-11-22", "queryKeyword", "gym"));
        assertEquals(1, byKeyword.getEvents().size());
        ActionOutcome byCategory = handle(action("QUERY_EVENTS", "queryFromDate", "2026-11-22", "queryCategory", "assignment"));
        assertEquals(1, byCategory.getEvents().size());
        assertTrue(byCategory.getReply().contains("DSA homework") && byCategory.getReply().contains("(ASSIGNMENT)"));
    }

    @Test
    void availabilityAnswersFreeOrBusyFromTheRealSchedule() {
        ActionOutcome free = handle(action("QUERY_EVENTS", "queryMode", "AVAILABILITY", "queryFromDate", "2026-11-22", "queryStartTime", "16:00"));
        assertTrue(free.getReply().startsWith("Yes, you're free on Sun, Nov 22, 4:00 PM \u2013 5:00 PM"), free.getReply());

        addEvent(f, U, "Gym", "2026-11-22", "15:30", "16:30");
        ActionOutcome busy = handle(action("QUERY_EVENTS", "queryMode", "AVAILABILITY", "queryFromDate", "2026-11-22", "queryStartTime", "16:00"));
        assertTrue(busy.getReply().contains("not free") && busy.getReply().contains("\"Gym\""), busy.getReply());
        assertFalse(busy.getSuggestions().isEmpty());
        for (Slot s : busy.getSuggestions()) assertTrue(f.conflictService.findConflicts(U, s.start(), s.end(), null).isEmpty());
    }

    @Test
    void availabilityWithoutATimeAsksForIt() {
        assertEquals(AiStatus.NEEDS_CLARIFICATION, handle(action("QUERY_EVENTS", "queryMode", "AVAILABILITY", "queryFromDate", "2026-11-22")).getStatus());
    }

    @Test
    void badQueryDatesAreAskedAgain() {
        assertEquals(AiStatus.NEEDS_CLARIFICATION, handle(action("QUERY_EVENTS", "queryFromDate", "next week-ish")).getStatus());
    }

    // ------------------------------------------------------------------ UPDATE

    @Test
    void movingTheStartKeepsTheEventsDuration() {
        Event e = addEvent(f, U, "Maths", "2026-11-22", "10:00", "11:30");
        ActionOutcome o = handle(action("UPDATE_EVENT", "targetEventId", e.getId(), "startTime", "14:00"));
        assertEquals(AiStatus.COMPLETED, o.getStatus());
        Event saved = f.eventService.getById(U, e.getId());
        assertEquals(ist("2026-11-22", "14:00"), saved.getStartTime());
        assertEquals(ist("2026-11-22", "15:30"), saved.getEndTime());
    }

    @Test
    void changingOnlyTheDateKeepsTheTime() {
        Event e = addEvent(f, U, "Assignment", "2026-11-22", "17:00", "18:00");
        handle(action("UPDATE_EVENT", "targetEventId", e.getId(), "date", "2026-11-27"));
        assertEquals(ist("2026-11-27", "17:00"), f.eventService.getById(U, e.getId()).getStartTime());
    }

    @Test
    void changingOnlyDetailsLeavesTheTimeAlone() {
        Event e = addEvent(f, U, "Maths", "2026-11-22", "10:00", "11:00");
        handle(action("UPDATE_EVENT", "targetEventId", e.getId(), "title", "Advanced Maths", "location", "Room 9"));
        Event saved = f.eventService.getById(U, e.getId());
        assertEquals("Advanced Maths", saved.getTitle());
        assertEquals("Room 9", saved.getLocation());
        assertEquals(ist("2026-11-22", "10:00"), saved.getStartTime());
    }

    @Test
    void anUpdateThatCollidesNeedsConfirmationAndChangesNothingUntilThen() {
        Event maths = addEvent(f, U, "Maths", "2026-11-22", "10:00", "11:00");
        addEvent(f, U, "Lab", "2026-11-22", "14:00", "15:00");
        ActionOutcome o = handle(action("UPDATE_EVENT", "targetEventId", maths.getId(), "startTime", "14:00"));
        assertEquals(AiStatus.NEEDS_CONFIRMATION, o.getStatus());
        assertEquals(ist("2026-11-22", "10:00"), f.eventService.getById(U, maths.getId()).getStartTime(), "unchanged so far");
        f.actionService.executePending(U, IST, NOW, o.getPending());
        assertEquals(ist("2026-11-22", "14:00"), f.eventService.getById(U, maths.getId()).getStartTime());
    }

    @Test
    void anEventNeverConflictsWithItselfWhenItIsExtended() {
        Event e = addEvent(f, U, "Maths", "2026-11-22", "10:00", "11:00");
        ActionOutcome o = handle(action("UPDATE_EVENT", "targetEventId", e.getId(), "endTime", "12:00"));
        assertEquals(AiStatus.COMPLETED, o.getStatus());
    }

    @Test
    void movingAnEventIntoThePastIsRefused() {
        Event e = addEvent(f, U, "Maths", "2026-11-22", "10:00", "11:00");
        ActionOutcome o = handle(action("UPDATE_EVENT", "targetEventId", e.getId(), "date", "2026-11-21", "startTime", "09:00"));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, o.getStatus());
        assertEquals(ist("2026-11-22", "10:00"), f.eventService.getById(U, e.getId()).getStartTime());
    }

    @Test
    void anUpdateWithNothingToChangeAsksWhatToChange() {
        Event e = addEvent(f, U, "Maths", "2026-11-22", "10:00", "11:00");
        ActionOutcome o = handle(action("UPDATE_EVENT", "targetEventId", e.getId()));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, o.getStatus());
        assertTrue(o.getReply().contains("What would you like to change"));
    }

    // ------------------------------------------------------------------ finding the right event

    @Test
    void anEventCanBeFoundByAHintWhenThereIsExactlyOneMatch() {
        Event e = addEvent(f, U, "Maths class", "2026-11-22", "10:00", "11:00");
        addEvent(f, U, "Gym", "2026-11-22", "07:00", "08:00");
        ActionOutcome o = handle(action("UPDATE_EVENT", "targetHint", "maths", "startTime", "12:00"));
        assertEquals(AiStatus.COMPLETED, o.getStatus());
        assertEquals(ist("2026-11-22", "12:00"), f.eventService.getById(U, e.getId()).getStartTime());
    }

    @Test
    void severalMatchingEventsMeanTheUserIsAskedWhichOne() {
        Event a = addEvent(f, U, "Maths", "2026-11-22", "10:00", "11:00");
        addEvent(f, U, "Maths lab", "2026-11-23", "10:00", "11:00");
        ActionOutcome o = handle(action("UPDATE_EVENT", "targetHint", "maths", "startTime", "12:00"));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, o.getStatus());
        assertTrue(o.getReply().contains("Maths lab") && o.getReply().contains("several"), o.getReply());
        assertEquals(ist("2026-11-22", "10:00"), f.eventService.getById(U, a.getId()).getStartTime());
    }

    @Test
    void noMatchOrNoIdentificationMeansAQuestionNotAGuess() {
        addEvent(f, U, "Gym", "2026-11-22", "07:00", "08:00");
        assertEquals(AiStatus.NEEDS_CLARIFICATION, handle(action("CANCEL_EVENT", "targetHint", "dentist")).getStatus());
        assertEquals(AiStatus.NEEDS_CLARIFICATION, handle(action("CANCEL_EVENT")).getStatus());
        assertEquals(AiStatus.NEEDS_CLARIFICATION, handle(action("UPDATE_EVENT", "startTime", "10:00")).getStatus());
    }

    @Test
    void aMadeUpEventIdIsTreatedAsNotFound() {
        assertEquals(AiStatus.NEEDS_CLARIFICATION, handle(action("CANCEL_EVENT", "targetEventId", "does-not-exist")).getStatus());
    }

    @Test
    void anotherUsersEventIdIsTreatedAsNotFoundAndNothingChanges() {
        Event bobs = addEvent(f, "u2", "Bob's dentist", "2026-11-22", "10:00", "11:00");
        ActionOutcome update = handle(action("UPDATE_EVENT", "targetEventId", bobs.getId(), "startTime", "15:00"));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, update.getStatus());
        ActionOutcome cancel = handle(action("CANCEL_EVENT", "targetEventId", bobs.getId()));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, cancel.getStatus());
        assertNull(cancel.getPending(), "no proposal may be created for someone else's event");
        assertEquals(ist("2026-11-22", "10:00"), f.eventService.getById("u2", bobs.getId()).getStartTime());
        assertFalse(update.getReply().contains("dentist"), "must not reveal the other user's event");
    }

    @Test
    void aHintNeverMatchesAnotherUsersEvents() {
        addEvent(f, "u2", "Dentist", "2026-11-22", "10:00", "11:00");
        ActionOutcome o = handle(action("CANCEL_EVENT", "targetHint", "dentist"));
        assertEquals(AiStatus.NEEDS_CLARIFICATION, o.getStatus());
        assertNull(o.getPending());
    }

    // ------------------------------------------------------------------ CANCEL

    @Test
    void cancelAlwaysNeedsConfirmationAndOnlyDeletesAfterwards() {
        Event e = addEvent(f, U, "Gym", "2026-11-22", "07:00", "08:00");
        ActionOutcome proposal = handle(action("CANCEL_EVENT", "targetEventId", e.getId()));
        assertEquals(AiStatus.NEEDS_CONFIRMATION, proposal.getStatus());
        assertEquals("CANCEL_EVENT", proposal.getPending().getType());
        assertEquals(e.getId(), proposal.getPending().getTargetEventId());
        assertEquals(1, countEvents(f, U), "still there until confirmed");

        ActionOutcome done = f.actionService.executePending(U, IST, NOW, proposal.getPending());
        assertEquals(AiStatus.COMPLETED, done.getStatus());
        assertEquals("", done.getLastEventId(), "the conversation must forget the deleted event");
        assertEquals(0, countEvents(f, U));
    }

    @Test
    void confirmingTheCancelOfAnAlreadyDeletedEventIsHandledGracefully() {
        Event e = addEvent(f, U, "Gym", "2026-11-22", "07:00", "08:00");
        ActionOutcome proposal = handle(action("CANCEL_EVENT", "targetEventId", e.getId()));
        f.eventService.delete(U, e.getId());
        ActionOutcome done = f.actionService.executePending(U, IST, NOW, proposal.getPending());
        assertEquals(AiStatus.INFO, done.getStatus());
        assertTrue(done.getReply().contains("couldn't find"));
    }

    @Test
    void aPendingActionCannotBeUsedToTouchAnotherUsersEvent() {
        Event bobs = addEvent(f, "u2", "Bob's dentist", "2026-11-22", "10:00", "11:00");
        PendingAiAction forged = new PendingAiAction();
        forged.setId("x");
        forged.setType("CANCEL_EVENT");
        forged.setTargetEventId(bobs.getId());
        ActionOutcome o = f.actionService.executePending(U, IST, NOW, forged);
        assertEquals(AiStatus.INFO, o.getStatus());
        assertEquals("Bob's dentist", f.eventService.getById("u2", bobs.getId()).getTitle());
    }

    @Test
    void aConfirmedCreateWhoseTimeHasPassedInTheMeantimeIsNotCreated() {
        addEvent(f, U, "Maths", "2026-11-22", "17:00", "18:00");
        ActionOutcome proposal = handle(action("CREATE_EVENT", "title", "DSA", "date", "2026-11-22", "startTime", "17:30"));
        Instant muchLater = ist("2026-11-23", "09:00");
        ActionOutcome o = f.actionService.executePending(U, IST, muchLater, proposal.getPending());
        assertEquals(AiStatus.INFO, o.getStatus());
        assertEquals(1, countEvents(f, U));
    }
}
