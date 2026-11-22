package com.hrms.backend.dtos.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What the app sends to POST /ai/confirm when the user taps "Confirm" or "Cancel".
 *
 * @param pendingActionId the id the server gave in GeminiChatResponse.pendingAction.id
 * @param confirm         true = go ahead, false = don't
 */
public record GeminiConfirmRequest(
        @NotBlank(message = "pendingActionId is required") @Size(max = 64) String pendingActionId,
        boolean confirm,
        @Size(max = 64) String timeZone) {
}
