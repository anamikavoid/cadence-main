package com.hrms.backend.services.aiService;

import com.hrms.backend.dtos.entityDtos.Event.EventRequestDto;
import com.hrms.backend.exceptions.BadApiRequestException;
import com.hrms.backend.exceptions.ResourceNotFoundException;
import com.hrms.backend.models.Event;
import com.hrms.backend.models.enums.EventCategory;
import com.hrms.backend.models.enums.EventPriority;
import com.hrms.backend.services.eventService.EventServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.hrms.backend.services.aiService.AiTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The event service: validation, defaults and (most importantly) ownership. */
class EventServiceTest {
    private AiTestSupport.Fixture f;

    @BeforeEach
    void setUp() {
        f = AiTestSupport.Fixture.create();
    }

    private EventRequestDto dto(String title, Instant start, Instant end) {
        EventRequestDto d = new EventRequestDto();
        d.setTitle(title);
        d.setStartTime(start);
        d.setEndTime(end);
        return d;
    }

    @Test
    void createSetsOwnerDefaultsAndAOneHourDuration() {
        Event e = f.eventService.create("u1", dto("  Study  ", ist("2026-11-22", "10:00"), null), "MANUAL");
        assertEquals("u1", e.getUserId());
        assertEquals("Study", e.getTitle());
        assertEquals(EventCategory.OTHER, e.getCategory());
        assertEquals(EventPriority.MEDIUM, e.getPriority());
        assertEquals(ist("2026-11-22", "11:00"), e.getEndTime());
        assertEquals("MANUAL", e.getSource());
        assertNotNull(e.getId());
    }

    @Test
    void invalidEventsAreRejected() {
        assertThrows(BadApiRequestException.class, () -> f.eventService.create("u1", dto("  ", ist("2026-11-22", "10:00"), null), "MANUAL"));
        assertThrows(BadApiRequestException.class, () -> f.eventService.create("u1", dto("x", null, null), "MANUAL"));
        assertThrows(BadApiRequestException.class, () -> f.eventService.create("u1", dto("x", ist("2026-11-22", "10:00"), ist("2026-11-22", "09:00")), "MANUAL"));
        assertThrows(BadApiRequestException.class, () -> f.eventService.create("u1", dto("x", ist("2026-11-22", "10:00"), ist("2026-11-22", "10:00")), "MANUAL"));
        Instant start = ist("2026-11-22", "10:00");
        assertThrows(BadApiRequestException.class, () -> f.eventService.create("u1", dto("x", start, start.plus(Duration.ofHours(25))), "MANUAL"));
        assertThrows(BadApiRequestException.class, () -> f.eventService.create("u1", dto("x".repeat(121), start, null), "MANUAL"));
    }

    @Test
    void aUserCannotReadAnotherUsersEvent() {
        Event e = addEvent(f, "u1", "Private", "2026-11-22", "10:00", "11:00");
        assertThrows(ResourceNotFoundException.class, () -> f.eventService.getById("u2", e.getId()));
        assertEquals("Private", f.eventService.getById("u1", e.getId()).getTitle());
    }

    @Test
    void aUserCannotUpdateAnotherUsersEvent() {
        Event e = addEvent(f, "u1", "Private", "2026-11-22", "10:00", "11:00");
        assertThrows(ResourceNotFoundException.class,
                () -> f.eventService.update("u2", e.getId(), dto("Hacked", ist("2026-11-22", "10:00"), ist("2026-11-22", "11:00"))));
        assertEquals("Private", f.eventService.getById("u1", e.getId()).getTitle());
    }

    @Test
    void aUserCannotDeleteAnotherUsersEvent() {
        Event e = addEvent(f, "u1", "Private", "2026-11-22", "10:00", "11:00");
        assertThrows(ResourceNotFoundException.class, () -> f.eventService.delete("u2", e.getId()));
        assertEquals("Private", f.eventService.getById("u1", e.getId()).getTitle());
    }

    @Test
    void ownersCanUpdateAndDelete() {
        Event e = addEvent(f, "u1", "Old", "2026-11-22", "10:00", "11:00");
        Event updated = f.eventService.update("u1", e.getId(), dto("New", ist("2026-11-22", "12:00"), ist("2026-11-22", "13:00")));
        assertEquals("New", updated.getTitle());
        assertEquals(ist("2026-11-22", "12:00"), f.eventService.getById("u1", e.getId()).getStartTime());
        f.eventService.delete("u1", e.getId());
        assertThrows(ResourceNotFoundException.class, () -> f.eventService.getById("u1", e.getId()));
    }

    @Test
    void listingOnlyReturnsTheCallersEvents() {
        addEvent(f, "u1", "Mine", "2026-11-22", "10:00", "11:00");
        addEvent(f, "u2", "Theirs", "2026-11-22", "10:00", "11:00");
        assertEquals(1, f.eventService.findBetween("u1", ist("2026-11-22", "00:00"), ist("2026-11-23", "00:00")).size());
        assertEquals("Mine", f.eventService.findBetween("u1", ist("2026-11-22", "00:00"), ist("2026-11-23", "00:00")).get(0).getTitle());
    }

    @Test
    void aMissingUserIdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> f.eventService.findBetween(null, ist("2026-11-22", "00:00"), ist("2026-11-23", "00:00")));
        assertThrows(IllegalArgumentException.class, () -> f.eventService.create("", dto("x", ist("2026-11-22", "10:00"), null), "AI"));
    }

    @Test
    void thereIsAPerUserEventLimit() {
        Instant start = ist("2026-11-22", "10:00");
        for (int i = 0; i < EventServiceImpl.MAX_EVENTS_PER_USER; i++) {
            f.eventService.create("u1", dto("e" + i, start.plus(Duration.ofMinutes(i)), null), "MANUAL");
        }
        assertThrows(BadApiRequestException.class, () -> f.eventService.create("u1", dto("one too many", start, null), "AI"));
        f.eventService.create("u2", dto("other users are not affected", start, null), "AI"); // must not throw
    }
}
