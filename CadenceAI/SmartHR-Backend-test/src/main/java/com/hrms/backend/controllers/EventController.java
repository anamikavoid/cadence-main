package com.hrms.backend.controllers;

import com.hrms.backend.dtos.entityDtos.Event.EventRequestDto;
import com.hrms.backend.dtos.entityDtos.Event.EventResponseDto;
import com.hrms.backend.exceptions.BadApiRequestException;
import com.hrms.backend.models.Event;
import com.hrms.backend.services.eventService.EventServiceInterface;
import com.hrms.backend.utils.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual schedule API (what the future calendar screens will call). Same service and same
 * ownership rules as the AI assistant.
 *
 *   GET    /events?from=&to=   events overlapping the window (ISO UTC, default: next 30 days)
 *   GET    /events/{id}
 *   POST   /events   409 if it overlaps another event
 *   PUT    /events/{id}
 *   DELETE /events/{id}
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private static final long MAX_RANGE_DAYS = 366;

    private final EventServiceInterface eventService;

    @Autowired
    public EventController(EventServiceInterface eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public ResponseEntity<List<EventResponseDto>> list(@RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to,
                                                       Authentication authentication) {
        String userId = CurrentUser.id(authentication);
        Instant start = parseInstant(from, "from");
        Instant end = parseInstant(to, "to");
        if (start == null) start = Instant.now();
        if (end == null) end = start.plus(Duration.ofDays(30));
        if (!end.isAfter(start)) throw new BadApiRequestException("'to' must be after 'from'");
        if (Duration.between(start, end).toDays() > MAX_RANGE_DAYS) throw new BadApiRequestException("The time range is too large");
        return ResponseEntity.ok(toDtos(eventService.findBetween(userId, start, end)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventResponseDto> get(@PathVariable String id, Authentication authentication) {
        return ResponseEntity.ok(EventResponseDto.from(eventService.getById(CurrentUser.id(authentication), id)));
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody EventRequestDto dto,
                                    Authentication authentication) {
        String userId = CurrentUser.id(authentication);
        Event created = eventService.create(userId, dto, "MANUAL");
        return ResponseEntity.status(HttpStatus.CREATED).body(EventResponseDto.from(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @Valid @RequestBody EventRequestDto dto,
                                    Authentication authentication) {
        String userId = CurrentUser.id(authentication);
        return ResponseEntity.ok(EventResponseDto.from(eventService.update(userId, id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication authentication) {
        eventService.delete(CurrentUser.id(authentication), id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ helpers

    private static Instant parseInstant(String text, String name) {
        if (text == null || text.isBlank()) return null;
        try {
            return Instant.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new BadApiRequestException("'" + name + "' must be an ISO-8601 UTC time such as 2026-11-22T00:00:00Z");
        }
    }

    private static List<EventResponseDto> toDtos(List<Event> events) {
        List<EventResponseDto> dtos = new ArrayList<>();
        for (Event e : events) dtos.add(EventResponseDto.from(e));
        return dtos;
    }
}
