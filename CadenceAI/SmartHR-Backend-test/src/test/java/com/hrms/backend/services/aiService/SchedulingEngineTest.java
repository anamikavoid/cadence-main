package com.hrms.backend.services.aiService;

import com.hrms.backend.controllers.SchedulingController;
import com.hrms.backend.exceptions.BadApiRequestException;
import com.hrms.backend.models.User;
import com.hrms.backend.services.scheduling.SchedulingEngine;
import com.hrms.backend.services.scheduling.SchedulingEngine.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import com.hrms.backend.exceptions.EventConflictException;

import java.time.*;
import java.util.List;

import static com.hrms.backend.models.enums.EventPriority.*;
import static com.hrms.backend.services.aiService.AiTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingEngineTest {
    private final Fixture f = Fixture.create();
    private final SchedulingEngine engine = new SchedulingEngine(f.eventService, Clock.fixed(NOW, IST));
    private final Instant start = ist("2026-11-22", "09:00");
    private final Slot window = new Slot(start, start.plus(Duration.ofHours(3)));
    private final Activity urgent = new Activity("Exam", 60, HIGH, hour(1));

    private Instant hour(int hours) {
        return start.plus(Duration.ofHours(hours));
    }

    @Test
    void placesByPriorityThenDeadlineAndReservesEachInterval() {
        Activity later = new Activity("Essay", 60, HIGH, hour(3));
        Activity low = new Activity("Read", 60, LOW, hour(1));
        var result = engine.propose("alice", new Request(window, List.of(low, later, urgent), List.of()));
        assertEquals(List.of(urgent, later), result.placements().stream().map(Placement::activity).toList());
        assertEquals(new Slot(start, hour(1)), result.placements().getFirst().slot());
        assertEquals(new Slot(hour(1), hour(2)), result.placements().getLast().slot());
        assertEquals(List.of(new Slot(hour(2), hour(3))), result.placements().getLast().alternatives());
        assertEquals(List.of(low), result.unplaced());
    }

    @Test
    void fixedEventStaysPutAndOnlyAnExplicitFlexibleMoveCanBeConfirmed() {
        var event = addEvent(f, "alice", "Study", "2026-11-22", "09:00", "10:00");
        event.setLocation("Library");
        f.eventRepo.save(event);
        assertEquals(List.of(urgent), engine.propose("alice", new Request(window, List.of(urgent), null)).unplaced());
        var request = new Request(window, List.of(urgent), List.of(event.getId()));
        var proposal = engine.propose("alice", request);
        Move move = proposal.movesToConfirm().getFirst();
        assertEquals(new Slot(hour(1), hour(2)), move.proposed());
        assertEquals(start, f.eventService.getById("alice", event.getId()).getStartTime());

        var controller = new SchedulingController(engine, f.eventService);
        User alice = new User();
        alice.setId("alice");
        var auth = new UsernamePasswordAuthenticationToken(alice, null);
        var confirmation = new SchedulingController.Confirmation(request, move);
        controller.confirm(auth, confirmation);
        var saved = f.eventService.getById("alice", event.getId());
        assertEquals(hour(1), saved.getStartTime());
        assertEquals("Library", saved.getLocation());
        assertThrows(EventConflictException.class, () -> controller.confirm(auth, confirmation));
    }

    @Test
    void validatesInputAndScopesFlexibleIdsToTheOwner() {
        var other = addEvent(f, "bob", "Study", "2026-11-22", "09:00", "10:00");
        assertThrows(BadApiRequestException.class, () -> engine.propose("alice",
                new Request(window, List.of(urgent), List.of(other.getId()))));
        assertThrows(BadApiRequestException.class, () -> engine.propose("alice",
                new Request(window, List.of(new Activity("Bad", 0, HIGH, hour(1))), null)));
    }

    @Test
    void preservesElapsedDurationAcrossSpringClockChange() {
        Instant from = OffsetDateTime.parse("2027-03-14T01:30:00-05:00").toInstant();
        Instant to = OffsetDateTime.parse("2027-03-14T03:30:00-04:00").toInstant();
        var activity = new Activity("Study", 60, MEDIUM, to);
        Slot window = new Slot(from, to);
        assertEquals(window, engine.propose("alice", new Request(window, List.of(activity), null)).placements().getFirst().slot());
    }
}
