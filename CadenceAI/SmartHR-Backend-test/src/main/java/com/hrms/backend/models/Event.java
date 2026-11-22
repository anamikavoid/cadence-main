package com.hrms.backend.models;

import com.hrms.backend.models.enums.EventCategory;
import com.hrms.backend.models.enums.EventPriority;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One entry in a user's personal schedule (a class, meeting, assignment deadline, ...).
 *
 * DATABASE: stored in the MongoDB collection "events". One document per event.
 *
 * WHO OWNS IT: {@code userId} is the id of the user who owns the event. EVERY query in
 * EventRepository filters by userId, which is how users are kept from seeing each other's data.
 *
 * TIME: startTime/endTime are {@link Instant}s (a point on the UTC timeline). We convert to the
 * user's local time zone only when we show them to the user or to Gemini.
 */
@Getter
@Setter
@NoArgsConstructor
@Document(collection = "events")
// Speeds up "give me this user's events between two times", the most common query.
@CompoundIndex(name = "user_start_idx", def = "{'userId': 1, 'startTime': 1}")
public class Event {
    @Id
    private String id;
    private String userId;
    private String title;
    private String description;
    private EventCategory category;
    private EventPriority priority;
    private Instant startTime;
    private Instant endTime;
    private String location;
    /** "MANUAL" (created through the events API) or "AI" (created by the assistant). */
    private String source;
    private Instant createdAt;
    private Instant updatedAt;
}
