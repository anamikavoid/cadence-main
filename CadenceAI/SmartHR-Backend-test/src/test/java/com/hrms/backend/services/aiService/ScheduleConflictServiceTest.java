package com.hrms.backend.services.aiService;

import com.hrms.backend.models.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static com.hrms.backend.services.aiService.AiTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Scheduling conflict detection and alternative-time suggestions (no database, no Gemini). */
class ScheduleConflictServiceTest {
    private AiTestSupport.Fixture f;

    @BeforeEach
    void setUp() {
        f = AiTestSupport.Fixture.create();
    }

    @Test
    void overlappingEventIsAConflict() {
        addEvent(f, "u1", "Maths", "2026-11-22", "10:00", "11:00");
        List<Event> c = f.conflictService.findConflicts("u1", ist("2026-11-22", "10:30"), ist("2026-11-22", "11:30"), null);
        assertEquals(1, c.size());
        assertEquals("Maths", c.get(0).getTitle());
    }

    @Test
    void newEventInsideAnExistingOneIsAConflict() {
        addEvent(f, "u1", "Maths", "2026-11-22", "10:00", "11:00");
        assertEquals(1, f.conflictService.findConflicts("u1", ist("2026-11-22", "10:15"), ist("2026-11-22", "10:45"), null).size());
    }

    @Test
    void eventsThatOnlyTouchDoNotConflict() {
        addEvent(f, "u1", "Maths", "2026-11-22", "10:00", "11:00");
        assertEquals(0, f.conflictService.findConflicts("u1", ist("2026-11-22", "11:00"), ist("2026-11-22", "12:00"), null).size());
        assertEquals(0, f.conflictService.findConflicts("u1", ist("2026-11-22", "09:00"), ist("2026-11-22", "10:00"), null).size());
    }

    @Test
    void anotherUsersEventIsNeverAConflict() {
        addEvent(f, "u2", "Bob's meeting", "2026-11-22", "10:00", "11:00");
        assertEquals(0, f.conflictService.findConflicts("u1", ist("2026-11-22", "10:00"), ist("2026-11-22", "11:00"), null).size());
    }

    @Test
    void anEventDoesNotConflictWithItselfWhenBeingMoved() {
        Event e = addEvent(f, "u1", "Maths", "2026-11-22", "10:00", "11:00");
        assertEquals(0, f.conflictService.findConflicts("u1", ist("2026-11-22", "10:30"), ist("2026-11-22", "11:30"), e.getId()).size());
    }

    @Test
    void freeSlotsAvoidBusyTimeStayInWorkingHoursAndAreSpreadOut() {
        Event a = addEvent(f, "u1", "A", "2026-11-22", "09:00", "10:00");
        Event b = addEvent(f, "u1", "B", "2026-11-22", "12:00", "13:00");
        List<Slot> slots = ScheduleConflictService.computeFreeSlots(List.of(a, b), IST, LocalDate.parse("2026-11-22"),
                Duration.ofMinutes(60), ist("2026-11-22", "09:00"), NOW, 3);
        assertEquals(3, slots.size());
        assertEquals(ist("2026-11-22", "08:00"), slots.get(0).start());
        for (Slot s : slots) {
            assertFalse(s.start().isBefore(ist("2026-11-22", "08:00")), "starts after 08:00");
            assertFalse(s.end().isAfter(ist("2026-11-22", "21:00")), "ends before 21:00");
            assertFalse(s.start().isBefore(a.getEndTime()) && s.end().isAfter(a.getStartTime()), "overlaps A");
            assertFalse(s.start().isBefore(b.getEndTime()) && s.end().isAfter(b.getStartTime()), "overlaps B");
        }
        for (int i = 1; i < slots.size(); i++) {
            assertTrue(Duration.between(slots.get(i - 1).start(), slots.get(i).start()).toMinutes() >= 60, "spread out");
        }
    }

    @Test
    void suggestionsAreNeverInThePast() {
        // "Now" is 14:00 India time on 21 Nov, so nothing before 14:00 may be suggested for that day.
        List<Slot> slots = ScheduleConflictService.computeFreeSlots(List.of(), IST, LocalDate.parse("2026-11-21"),
                Duration.ofMinutes(60), ist("2026-11-21", "09:00"), NOW, 5);
        assertFalse(slots.isEmpty());
        for (Slot s : slots) assertFalse(s.start().isBefore(NOW), "suggested a time in the past: " + s.start());
    }

    @Test
    void aFullyBookedDayHasNoSuggestions() {
        Event all = addEvent(f, "u1", "Conference", "2026-11-22", "08:00", "21:00");
        assertTrue(ScheduleConflictService.computeFreeSlots(List.of(all), IST, LocalDate.parse("2026-11-22"),
                Duration.ofMinutes(60), ist("2026-11-22", "10:00"), NOW, 3).isEmpty());
    }

    @Test
    void suggestAlternativesLooksAtTheNextDayWhenTheDayIsFull() {
        addEvent(f, "u1", "Conference", "2026-11-22", "08:00", "21:00");
        List<Slot> slots = f.conflictService.suggestAlternatives("u1", IST, ist("2026-11-22", "10:00"), 60, null, NOW, 3);
        assertFalse(slots.isEmpty());
        for (Slot s : slots) assertTrue(s.start().isAfter(ist("2026-11-22", "21:00")), "should be on the next day");
    }
}
