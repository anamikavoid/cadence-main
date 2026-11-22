package com.hrms.backend.dtos.ai;

import com.hrms.backend.dtos.entityDtos.Event.EventResponseDto;

import java.util.List;

/**
 * What POST /ai/chat and POST /ai/confirm return.
 *
 * @param status        see AiStatus
 * @param reply         the text to show in the assistant's chat bubble
 * @param action        the action this reply is about (AiAction name), may be null
 * @param events        events relevant to the reply (created/updated event, query results)
 * @param pendingAction non-null when status is NEEDS_CONFIRMATION: show Confirm / Cancel buttons
 * @param suggestions   alternative free time slots when there was a conflict
 */
public record GeminiChatResponse(
        String status,
        String reply,
        String action,
        List<EventResponseDto> events,
        PendingActionDto pendingAction,
        List<SuggestionDto> suggestions) {
}
