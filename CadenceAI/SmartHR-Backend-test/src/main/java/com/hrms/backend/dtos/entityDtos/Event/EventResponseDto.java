package com.hrms.backend.dtos.entityDtos.Event;

import com.hrms.backend.models.Event;

/** What the API returns for an event. Times are ISO-8601 UTC strings. The userId is not exposed. */
public record EventResponseDto(
        String id,
        String title,
        String description,
        String category,
        String priority,
        String startTime,
        String endTime,
        String location,
        String source) {

    public static EventResponseDto from(Event e) {
        return new EventResponseDto(
                e.getId(),
                e.getTitle(),
                e.getDescription(),
                e.getCategory() == null ? null : e.getCategory().name(),
                e.getPriority() == null ? null : e.getPriority().name(),
                e.getStartTime() == null ? null : e.getStartTime().toString(),
                e.getEndTime() == null ? null : e.getEndTime().toString(),
                e.getLocation(),
                e.getSource());
    }
}
