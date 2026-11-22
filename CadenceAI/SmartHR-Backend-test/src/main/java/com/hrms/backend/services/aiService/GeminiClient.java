package com.hrms.backend.services.aiService;

import java.util.Map;

/**
 * The ONLY thing the rest of the AI module knows about Gemini: "send these instructions and get
 * JSON text back". Hiding the real HTTP call behind this small interface means tests can plug in a
 * fake client that returns canned answers, so ordinary tests never call the real API or use credits.
 */
public interface GeminiClient {

    /**
     * @param systemInstruction the rules for the model
     * @param userContent       the context + the user's message
     * @param jsonSchema        JSON Schema the answer must follow
     * @return the model's answer as JSON text
     * @throws com.hrms.backend.exceptions.AiServiceException with a user-safe message on any failure
     */
    String generateJson(String systemInstruction, String userContent, Map<String, Object> jsonSchema);
}
