package com.hrms.backend.dtos.ai;

/** An alternative free time slot (ISO UTC times plus a label already formatted in the user's time zone). */
public record SuggestionDto(String start, String end, String label) {
}
