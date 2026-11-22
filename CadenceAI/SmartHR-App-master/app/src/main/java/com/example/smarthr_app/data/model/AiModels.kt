package com.example.smarthr_app.data.model

// ---------------------------------------------------------------------------------------------
// DATA MODELS for the AI assistant. Each class mirrors the JSON of one backend DTO (Java records
// in com.hrms.backend.dtos.ai). Retrofit + Gson turn JSON text into these Kotlin objects.
//
// Note what is NOT here: a user id. The phone never tells the server who it is; the server reads
// that from the login token (JWT) sent in the Authorization header.
// ---------------------------------------------------------------------------------------------

/** Body of POST /ai/chat. [timeZone] is the phone's zone (e.g. "Asia/Kolkata") so "5 PM" means the user's 5 PM. */
data class AiChatRequest(
    val message: String,
    val timeZone: String
)

/** Body of POST /ai/confirm, sent when the user taps Confirm / Cancel on a proposed action. */
data class AiConfirmRequest(
    val pendingActionId: String,
    val confirm: Boolean,
    val timeZone: String
)

/** An event as returned by the backend (times are ISO-8601 UTC text like "2026-11-22T11:30:00Z"). */
data class AiEventDto(
    val id: String?,
    val title: String?,
    val description: String?,
    val category: String?,
    val priority: String?,
    val startTime: String?,
    val endTime: String?,
    val location: String?,
    val source: String?
)

/** An action the assistant proposed and is waiting for the user to confirm. [id] is issued by the server. */
data class AiPendingActionDto(
    val id: String,
    val type: String,        // CREATE_EVENT, UPDATE_EVENT or CANCEL_EVENT
    val summary: String?
)

/** An alternative free time slot suggested after a scheduling conflict. */
data class AiSuggestionDto(
    val start: String?,
    val end: String?,
    val label: String?
)

/**
 * The assistant's answer.
 * [status]: COMPLETED (action done), NEEDS_CONFIRMATION (show Confirm/Cancel buttons),
 * NEEDS_CLARIFICATION (the assistant asked a question) or INFO (just information).
 */
data class AiChatResponse(
    val status: String,
    val reply: String,
    val action: String?,
    val events: List<AiEventDto>?,
    val pendingAction: AiPendingActionDto?,
    val suggestions: List<AiSuggestionDto>?
)

/** One stored chat line. [role] is "USER" or "ASSISTANT". */
data class AiMessageDto(
    val role: String,
    val text: String,
    val timestamp: String?
)

/** Response of GET /ai/history: lets the chat screen restore the conversation. */
data class AiHistoryResponse(
    val messages: List<AiMessageDto>?,
    val pendingAction: AiPendingActionDto?
)
