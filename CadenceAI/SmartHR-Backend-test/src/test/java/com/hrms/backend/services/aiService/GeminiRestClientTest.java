package com.hrms.backend.services.aiService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrms.backend.exceptions.AiServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP error mapping and response parsing of the real Gemini client. No network access is used. */
class GeminiRestClientTest {

    private final GeminiRestClient client = new GeminiRestClient("test-key", "gemini-3.8-flash", "https://example.invalid/v1beta",
            new RestTemplate(), new ObjectMapper());

    @Test
    void rateLimitFromGoogleBecomesTooManyRequests() {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, GeminiRestClient.mapHttpError(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}").getStatus());
    }

    @Test
    void invalidApiKeyBecomesAConfigurationErrorWithoutLeakingDetails() {
        AiServiceException e = GeminiRestClient.mapHttpError(400, "{\"error\":{\"message\":\"API key not valid. Please pass a valid API key.\"}}");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertTrue(e.getMessage().contains("not configured correctly"));
        assertFalse(e.getMessage().contains("API key not valid"), "raw Google message must not be shown");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, GeminiRestClient.mapHttpError(403, "").getStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, GeminiRestClient.mapHttpError(401, null).getStatus());
    }

    @Test
    void anUnknownModelAndServerErrorsAreReportedSafely() {
        assertTrue(GeminiRestClient.mapHttpError(404, "model not found").getMessage().contains("model"));
        assertTrue(GeminiRestClient.mapHttpError(503, "overloaded").getMessage().contains("temporarily unavailable"));
        assertEquals(HttpStatus.BAD_GATEWAY, GeminiRestClient.mapHttpError(400, "something else").getStatus());
    }

    @Test
    void extractsTheModelTextFromAResponse() {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"action\\\":\\\"GENERAL_RESPONSE\\\"}\"}]},\"finishReason\":\"STOP\"}]}";
        assertEquals("{\"action\":\"GENERAL_RESPONSE\"}", client.extractText(body));
    }

    @Test
    void skipsTheModelsInternalThoughtParts() {
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"thinking...\",\"thought\":true},{\"text\":\"ANSWER\"}]}}]}";
        assertEquals("ANSWER", client.extractText(body));
    }

    @Test
    void aBlockedPromptBecomesARefusal() {
        AiServiceException e = assertThrows(AiServiceException.class, () -> client.extractText("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatus());
    }

    @Test
    void aSafetyStoppedAnswerBecomesARefusal() {
        AiServiceException e = assertThrows(AiServiceException.class,
                () -> client.extractText("{\"candidates\":[{\"finishReason\":\"SAFETY\",\"content\":{\"parts\":[]}}]}"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatus());
    }

    @Test
    void emptyOrMalformedResponsesAreHandledWithoutCrashing() {
        for (String body : new String[]{null, "", "   ", "not json at all", "{\"candidates\":[]}", "{}", "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]}}]}"}) {
            AiServiceException e = assertThrows(AiServiceException.class, () -> client.extractText(body), "body: " + body);
            assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRequestBodyFollowsTheDocumentedStructuredOutputShape() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> body = GeminiRestClient.buildRequestBody("SYSTEM RULES", "USER CONTENT", schema);

        Map<String, Object> system = (Map<String, Object>) body.get("systemInstruction");
        assertEquals("SYSTEM RULES", ((Map<String, Object>) ((List<Object>) system.get("parts")).get(0)).get("text"));
        Map<String, Object> user = (Map<String, Object>) ((List<Object>) body.get("contents")).get(0);
        assertEquals("user", user.get("role"));
        assertEquals("USER CONTENT", ((Map<String, Object>) ((List<Object>) user.get("parts")).get(0)).get("text"));
        Map<String, Object> text = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) body.get("generationConfig")).get("responseFormat")).get("text");
        assertEquals("APPLICATION_JSON", text.get("mimeType"));
        assertEquals(schema, text.get("schema"));
        assertFalse(body.toString().contains("test-key"), "the API key must never be part of the body");
    }

    @Test
    void withoutAnApiKeyTheAssistantReportsItIsNotConfiguredAndDoesNotCallOut() {
        GeminiRestClient noKey = new GeminiRestClient("", "gemini-3.8-flash", "https://example.invalid", new RestTemplate(), new ObjectMapper());
        AiServiceException e = assertThrows(AiServiceException.class, () -> noKey.generateJson("s", "u", Map.of()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatus());
        assertTrue(e.getMessage().contains("not configured"));
    }

    @Test
    void aSuspiciousModelNameIsRefusedBecauseItWouldEndUpInTheUrl() {
        GeminiRestClient bad = new GeminiRestClient("k", "../../evil?x=", "https://example.invalid", new RestTemplate(), new ObjectMapper());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(AiServiceException.class, () -> bad.generateJson("s", "u", Map.of())).getStatus());
    }
}
