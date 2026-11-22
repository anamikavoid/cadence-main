package com.hrms.backend.dtos.ai;

import java.util.List;

/** What GET /ai/history returns, so the chat screen can restore the conversation. */
public record AiHistoryResponse(List<AiMessageDto> messages, PendingActionDto pendingAction) {
}
