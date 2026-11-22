package com.hrms.backend.dtos.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The JSON object Gemini must return for every message (its "intended action").
 *
 * IMPORTANT: this is only a PROPOSAL. Nothing in here is trusted. GeminiActionService validates
 * every field (dates, times, enums, event ids, ownership, conflicts) before anything is saved.
 *
 * All fields are optional text because the model fills only the ones relevant to the action;
 * unused fields are null. Dates are "yyyy-MM-dd", times are 24-hour "HH:mm", in the USER's time zone.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiActionResponse {
    /** One of AiAction. */
    private String action;
    /** Text for ASK_CLARIFICATION (the question) and GENERAL_RESPONSE (the answer). */
    private String reply;

    // CREATE_EVENT: all fields. UPDATE_EVENT: only the fields that change.
    private String title;
    private String description;
    private String category;
    private String priority;
    private String location;
    private String date;
    private String startTime;
    private String endTime;
    private Integer durationMinutes;

    // UPDATE_EVENT / CANCEL_EVENT: which event.
    private String targetEventId;
    private String targetHint;

    // QUERY_EVENTS
    /** "LIST" or "AVAILABILITY". */
    private String queryMode;
    private String queryFromDate;
    private String queryToDate;
    private String queryStartTime;
    private String queryEndTime;
    private String queryKeyword;
    private String queryCategory;
}
