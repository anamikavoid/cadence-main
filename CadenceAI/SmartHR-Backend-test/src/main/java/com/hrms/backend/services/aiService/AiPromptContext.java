package com.hrms.backend.services.aiService;

import com.hrms.backend.models.AiMessage;
import com.hrms.backend.models.Event;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Everything (besides the user's message) that Gemini is allowed to see for ONE request.
 * Built by AiChatService from THIS user's data only, which is what keeps users isolated.
 */
public record AiPromptContext(
        ZoneId zone,
        Instant now,
        List<Event> events,
        List<AiMessage> recentMessages,
        String pendingSummary,
        String lastEventId) {
}
