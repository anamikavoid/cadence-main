package com.hrms.backend.dtos.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the app sends to POST /ai/chat.
 * The user id is NOT part of the request: it comes from the login token (JWT).
 *
 * @param message  what the user typed
 * @param timeZone the phone's IANA time zone, e.g. "Asia/Kolkata", so "tomorrow 5 PM" means the user's 5 PM
 */
public record GeminiChatRequest(
        @NotBlank(message = "Please type a message") @Size(max = 1000, message = "Your message is too long (maximum 1000 characters)") String message,
        @Size(max = 64, message = "Invalid time zone") String timeZone) {
}
