package com.hrms.backend.services.eventService;

import com.hrms.backend.dtos.entityDtos.Event.EventRequestDto;
import com.hrms.backend.models.Event;

import java.time.Instant;
import java.util.List;

/**
 * Business operations on a user's events. EVERY method takes the userId as its first argument and
 * only ever touches that user's events. The AI assistant and the manual REST API both go through
 * this interface, so the same validation rules apply no matter who asked.
 */
public interface EventServiceInterface {

    /** Creates an event owned by userId. {@code source} is "MANUAL" or "AI". */
    Event create(String userId, EventRequestDto dto, String source);

    /** Replaces the editable fields of an event. Throws ResourceNotFoundException if it is not the user's. */
    Event update(String userId, String eventId, EventRequestDto dto);

    /** Deletes an event. Throws ResourceNotFoundException if it is not the user's. */
    void delete(String userId, String eventId);

    /** Throws ResourceNotFoundException if the event does not exist OR belongs to someone else. */
    Event getById(String userId, String eventId);

    /** The user's events overlapping [from, to), earliest first. */
    List<Event> findBetween(String userId, Instant from, Instant to);
}
