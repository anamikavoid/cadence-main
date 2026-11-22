package com.hrms.backend.dtos.ai;

/** Values of the "status" field of GeminiChatResponse; the Android app switches on these. */
public final class AiStatus {
    private AiStatus() {}

    /** The action was carried out (event created, updated or cancelled). */
    public static final String COMPLETED = "COMPLETED";
    /** The assistant proposes an action and waits for yes/no (the response has a pendingAction). */
    public static final String NEEDS_CONFIRMATION = "NEEDS_CONFIRMATION";
    /** The assistant needs more information (it asked a question). */
    public static final String NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";
    /** Just information: schedule answers, general replies, "okay, not doing that". */
    public static final String INFO = "INFO";
}
