package com.hrms.backend.services.aiService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrms.backend.exceptions.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Talks to the Gemini REST API over HTTPS using Spring's RestTemplate (already in the project, so no
 * extra SDK/dependency).
 *
 * Request:  POST {base-url}/models/{model}:generateContent
 *           header  x-goog-api-key: <GEMINI_API_KEY>   (the key goes in a HEADER, never in the URL, so it is not logged)
 *           body    { systemInstruction, contents, generationConfig.responseFormat.text.schema }
 * (Request format checked against Google's "Structured outputs" documentation.)
 *
 * SECURITY: the key is read from the environment (GEMINI_API_KEY) and exists only on the server.
 * It is never returned in any API response, never put in an error message, and never logged.
 */
@Component
public class GeminiRestClient implements GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiRestClient.class);
    /** The model name goes into the URL path, so only allow harmless characters. */
    private static final Pattern MODEL_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public GeminiRestClient(@Value("${gemini.api-key:}") String apiKey,
                            @Value("${gemini.model:gemini-3.8-flash}") String model,
                            @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
                            @Value("${gemini.timeout-ms:30000}") int timeoutMs,
                            ObjectMapper objectMapper) {
        this(apiKey, model, baseUrl, buildRestTemplate(timeoutMs), objectMapper);
    }

    /** Test constructor: lets a test supply a RestTemplate that talks to a fake server. */
    GeminiRestClient(String apiKey, String model, String baseUrl, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** A dedicated RestTemplate WITH timeouts (the shared one in MyConfig has none and could hang forever). */
    private static RestTemplate buildRestTemplate(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(timeoutMs);
        return new RestTemplate(factory);
    }

    @Override
    public String generateJson(String systemInstruction, String userContent, Map<String, Object> jsonSchema) {
        if (apiKey.isEmpty() || !MODEL_NAME.matcher(model).matches()) {
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "The AI assistant is not configured on the server yet.");
        }
        Map<String, Object> body = buildRequestBody(systemInstruction, userContent, jsonSchema);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);
        String url = baseUrl + "/models/" + model + ":generateContent";

        for (int attempt = 1; ; attempt++) {
            try {
                ResponseEntity<String> response =
                        restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
                return extractText(response.getBody());
            } catch (HttpStatusCodeException e) {
                int status = e.getStatusCode().value();
                String errorBody = e.getResponseBodyAsString();
                // Technical details go to the server log only (never to the user).
                log.warn("Gemini returned HTTP {} (attempt {}): {}", status, attempt, shorten(errorBody));
                if (attempt == 1 && (status == 500 || status == 503)) {
                    pause(800); // Google-side hiccup: one quick retry
                    continue;
                }
                throw mapHttpError(status, errorBody);
            } catch (ResourceAccessException e) {
                log.warn("Gemini request failed ({})", e.getClass().getSimpleName());
                throw new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "The AI service did not respond in time. Please try again.");
            }
        }
    }

    /** Builds the JSON body. Kept in one method so a future change in Google's API is a one-place fix. */
    static Map<String, Object> buildRequestBody(String systemInstruction, String userContent, Map<String, Object> jsonSchema) {
        Map<String, Object> systemPart = new LinkedHashMap<>();
        systemPart.put("text", systemInstruction);
        Map<String, Object> userPart = new LinkedHashMap<>();
        userPart.put("text", userContent);

        Map<String, Object> system = new LinkedHashMap<>();
        system.put("parts", List.of(systemPart));

        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("parts", List.of(userPart));

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("mimeType", "APPLICATION_JSON");
        text.put("schema", jsonSchema);
        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("text", text);
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("responseFormat", responseFormat);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", system);
        body.put("contents", List.of(userMessage));
        body.put("generationConfig", generationConfig);
        return body;
    }

    /** Pulls the model's text out of Gemini's response JSON. */
    String extractText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "The AI service returned an empty response. Please try again.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "The AI service returned an unexpected response. Please try again.");
        }
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.size() == 0) {
            if (!root.path("promptFeedback").path("blockReason").asText("").isEmpty()) {
                throw new AiServiceException(HttpStatus.UNPROCESSABLE_ENTITY, "I can't help with that request.");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "The AI service returned an empty response. Please try again.");
        }
        JsonNode candidate = candidates.get(0);
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) continue; // skip the model's internal reasoning parts
            JsonNode t = part.get("text");
            if (t != null && t.isTextual()) text.append(t.asText());
        }
        if (text.length() == 0) {
            String finish = candidate.path("finishReason").asText("");
            if ("SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish) || "BLOCKLIST".equals(finish)) {
                throw new AiServiceException(HttpStatus.UNPROCESSABLE_ENTITY, "I can't help with that request.");
            }
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "The AI service returned an empty response. Please try again.");
        }
        return text.toString();
    }

    /** Maps Gemini's HTTP error to a message that is safe to show the user. */
    static AiServiceException mapHttpError(int status, String errorBody) {
        String b = errorBody == null ? "" : errorBody;
        if (status == 429) {
            return new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "The AI assistant is busy right now. Please try again in a minute.");
        }
        boolean badKey = status == 401 || status == 403
                || (status == 400 && (b.contains("API_KEY_INVALID") || b.contains("API key not valid") || b.contains("API key expired")));
        if (badKey) {
            return new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "The AI assistant is not configured correctly. Please contact the administrator.");
        }
        if (status == 404) {
            return new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "The configured AI model is not available. Please contact the administrator.");
        }
        if (status >= 500) {
            return new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "The AI service is temporarily unavailable. Please try again in a moment.");
        }
        return new AiServiceException(HttpStatus.BAD_GATEWAY, "The AI service could not process this request.");
    }

    private static String shorten(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ");
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
