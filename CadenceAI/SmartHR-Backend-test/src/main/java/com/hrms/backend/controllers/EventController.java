package com.hrms.backend.controllers;

import com.hrms.backend.dtos.entityDtos.Event.EventRequestDto;
import com.hrms.backend.dtos.entityDtos.Event.EventResponseDto;
import com.hrms.backend.dtos.response_message.ErrorApiResponseMessage;
import com.hrms.backend.exceptions.BadApiRequestException;
import com.hrms.backend.models.Event;
import com.hrms.backend.services.aiService.ScheduleConflictService;
import com.hrms.backend.services.eventService.EventServiceImpl;
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
 *   POST   /events[?force=true]   409 if it overlaps another event, unless force=true
 *   PUT    /events/{id}[?force=true]
 *   DELETE /events/{id}
 */
@RestController
@RequestMapping("/events")
public class EventController {

    private static final long MAX_RANGE_DAYS = 366;

    private final EventServiceInterface eventService;
    private final ScheduleConflictService conflictService;

    @Autowired
    public EventController(EventServiceInterface eventService, ScheduleConflictService conflictService) {
        this.eventService = eventService;
        this.conflictService = conflictService;
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
                                    @RequestParam(defaultValue = "false") boolean force,
                                    Authentication authentication) {
        String userId = CurrentUser.id(authentication);
        if (!force) {
            ResponseEntity<ErrorApiResponseMessage> conflict = conflictResponse(userId, dto, null);
            if (conflict != null) return conflict;
        }
        Event created = eventService.create(userId, dto, "MANUAL");
        return ResponseEntity.status(HttpStatus.CREATED).body(EventResponseDto.from(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @Valid @RequestBody EventRequestDto dto,
                                    @RequestParam(defaultValue = "false") boolean force,
                                    Authentication authentication) {
        String userId = CurrentUser.id(authentication);
        if (!force) {
            ResponseEntity<ErrorApiResponseMessage> conflict = conflictResponse(userId, dto, id);
            if (conflict != null) return conflict;
        }
        return ResponseEntity.ok(EventResponseDto.from(eventService.update(userId, id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication authentication) {
        eventService.delete(CurrentUser.id(authentication), id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ helpers

    private ResponseEntity<ErrorApiResponseMessage> conflictResponse(String userId, EventRequestDto dto, String excludeId) {
        Instant start = dto.getStartTime();
        if (start == null) return null; // validation will reject it
        Instant end = dto.getEndTime() != null ? dto.getEndTime() : start.plus(Duration.ofMinutes(EventServiceImpl.DEFAULT_DURATION_MINUTES));
        if (!end.isAfter(start)) return null; // the service will reject it with a clear message
        List<Event> conflicts = conflictService.findConflicts(userId, start, end, excludeId);
        if (conflicts.isEmpty()) return null;
        String message = "This overlaps with \"" + conflicts.get(0).getTitle() + "\""
                + (conflicts.size() > 1 ? " and " + (conflicts.size() - 1) + " more" : "")
                + ". Send it again with ?force=true to save it anyway.";
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorApiResponseMessage.builder().error(message).build());
    }

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
