package com.hrms.backend.dtos.ai;

/** The only actions Gemini is allowed to ask for. Anything else is rejected as an invalid response. */
public enum AiAction {
    CREATE_EVENT, UPDATE_EVENT, CANCEL_EVENT, QUERY_EVENTS, ASK_CLARIFICATION, GENERAL_RESPONSE;

    /** Returns null (instead of throwing) when the text is not a known action. */
    public static AiAction parse(String text) {
        if (text == null) return null;
        try {
            return AiAction.valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
