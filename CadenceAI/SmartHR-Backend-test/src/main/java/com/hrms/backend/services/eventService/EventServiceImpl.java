package com.hrms.backend.services.eventService;

import com.hrms.backend.dtos.entityDtos.Event.EventRequestDto;
import com.hrms.backend.exceptions.BadApiRequestException;
import com.hrms.backend.exceptions.ResourceNotFoundException;
import com.hrms.backend.models.Event;
import com.hrms.backend.models.enums.EventCategory;
import com.hrms.backend.models.enums.EventPriority;
import com.hrms.backend.repositories.EventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hrms.backend.services.aiService.ScheduleConflictService;
import com.hrms.backend.exceptions.EventConflictException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * SERVICE layer = the business rules. The controller (or the AI) calls this class; this class
 * validates and then uses the repository to read/write MongoDB.
 */
@Service
public class EventServiceImpl implements EventServiceInterface {

    public static final long DEFAULT_DURATION_MINUTES = 60;
    public static final int MAX_EVENTS_PER_USER = 2000;
    private static final Duration MAX_DURATION = Duration.ofHours(24);

    private final EventRepository eventRepository;

    private final ScheduleConflictService conflicts;

    @Autowired
    public EventServiceImpl(EventRepository eventRepository, ScheduleConflictService conflicts) {
        this.eventRepository = eventRepository;
        this.conflicts = conflicts;
    }

    @Override
    public synchronized Event create(String userId, EventRequestDto dto, String source) {
        requireUser(userId);
        // Abuse protection: one user (or a misbehaving AI loop) cannot fill the database.
        if (eventRepository.countByUserId(userId) >= MAX_EVENTS_PER_USER) {
            throw new BadApiRequestException("You have reached the maximum number of events (" + MAX_EVENTS_PER_USER + ").");
        }
        Event event = new Event();
        event.setUserId(userId); // ownership is set by the server, never by the client
        event.setSource(source == null ? "MANUAL" : source);
        event.setCreatedAt(Instant.now());
        applyDto(event, dto);
        requireFree(event);
        return eventRepository.save(event);
    }

    @Override
    public synchronized Event update(String userId, String eventId, EventRequestDto dto) {
        Event event = getById(userId, eventId); // 404 if not this user's event
        Instant previousStart = event.getStartTime();
        Instant previousEnd = event.getEndTime();
        applyDto(event, dto);
        if (!previousStart.equals(event.getStartTime()) || !previousEnd.equals(event.getEndTime())) {
            requireFree(event);
        }
        return eventRepository.save(event);
    }

    @Override
    public synchronized void delete(String userId, String eventId) {
        requireUser(userId);
        long deleted = eventRepository.deleteByIdAndUserId(eventId, userId);
        if (deleted == 0) {
            throw new ResourceNotFoundException("Event not found");
        }
    }

    @Override
    public Event getById(String userId, String eventId) {
        requireUser(userId);
        if (eventId == null || eventId.isBlank()) {
            throw new ResourceNotFoundException("Event not found");
        }
        // Same message whether the id does not exist or belongs to another user: we never reveal which.
        return eventRepository.findByIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found"));
    }

    @Override
    public List<Event> findBetween(String userId, Instant from, Instant to) {
        requireUser(userId);
        return eventRepository.findByUserIdAndStartTimeLessThanAndEndTimeGreaterThanOrderByStartTimeAsc(userId, to, from);
    }

    // ---------------------------------------------------------------- helpers

    @Override
    public synchronized Event confirmMove(String userId, String id, Instant previousStart, Instant previousEnd, Instant start) {
        Event event = getById(userId, id);
        if (!event.getStartTime().equals(previousStart) || !event.getEndTime().equals(previousEnd)) {
            throw new EventConflictException("The event changed. Request a fresh proposal.");
        }
        event.setStartTime(start);
        event.setEndTime(start.plus(Duration.between(previousStart, previousEnd)));
        requireFree(event);
        event.setUpdatedAt(Instant.now());
        return eventRepository.save(event);
    }

    private void requireFree(Event event) {
        if (!conflicts.findConflicts(event.getUserId(), event.getStartTime(), event.getEndTime(), event.getId()).isEmpty()) {
            throw new EventConflictException("That time overlaps another event.");
        }
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    /** Validates the dto and copies it onto the entity (shared by create and update). */
    private void applyDto(Event event, EventRequestDto dto) {
        if (dto == null) throw new BadApiRequestException("Event data is required");
        String title = dto.getTitle() == null ? "" : dto.getTitle().trim();
        if (title.isEmpty()) throw new BadApiRequestException("Title is required");
        if (title.length() > 120) throw new BadApiRequestException("Title must be at most 120 characters");
        if (dto.getDescription() != null && dto.getDescription().length() > 500) {
            throw new BadApiRequestException("Description must be at most 500 characters");
        }
        if (dto.getLocation() != null && dto.getLocation().length() > 120) {
            throw new BadApiRequestException("Location must be at most 120 characters");
        }
        if (dto.getStartTime() == null) throw new BadApiRequestException("Start time is required");

        Instant start = dto.getStartTime();
        Instant end = dto.getEndTime() != null ? dto.getEndTime() : start.plus(Duration.ofMinutes(DEFAULT_DURATION_MINUTES));
        if (!end.isAfter(start)) throw new BadApiRequestException("End time must be after the start time");
        if (Duration.between(start, end).compareTo(MAX_DURATION) > 0) {
            throw new BadApiRequestException("An event cannot be longer than 24 hours");
        }

        event.setTitle(title);
        event.setDescription(dto.getDescription() == null || dto.getDescription().isBlank() ? null : dto.getDescription().trim());
        event.setCategory(dto.getCategory() == null ? EventCategory.OTHER : dto.getCategory());
        event.setPriority(dto.getPriority() == null ? EventPriority.MEDIUM : dto.getPriority());
        event.setStartTime(start);
        event.setEndTime(end);
        event.setLocation(dto.getLocation() == null || dto.getLocation().isBlank() ? null : dto.getLocation().trim());
        event.setUpdatedAt(Instant.now());
    }
}
