package com.hrms.backend.dtos.entityDtos.Event;

import com.hrms.backend.models.enums.EventCategory;
import com.hrms.backend.models.enums.EventPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The JSON the app sends to create/replace an event. Times are ISO-8601 UTC strings such as
 * "2026-11-22T11:30:00Z". The user id is NOT here: it always comes from the login token.
 */
@Getter
@Setter
@NoArgsConstructor
public class EventRequestDto {
    @NotBlank(message = "Title is required")
    @Size(max = 120, message = "Title must be at most 120 characters")
    private String title;

    @Size(max = 500, message = "Description must be at most 500 characters")
    private String description;

    private EventCategory category;
    private EventPriority priority;

    @NotNull(message = "Start time is required")
    private Instant startTime;

    /** Optional. When missing the service uses a default duration of 60 minutes. */
    private Instant endTime;

    @Size(max = 120, message = "Location must be at most 120 characters")
    private String location;
}
