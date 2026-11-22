package com.hrms.backend.controllers;

import com.hrms.backend.dtos.ai.AiHistoryResponse;
import com.hrms.backend.dtos.ai.GeminiChatRequest;
import com.hrms.backend.dtos.ai.GeminiChatResponse;
import com.hrms.backend.dtos.ai.GeminiConfirmRequest;
import com.hrms.backend.services.aiService.AiChatService;
import com.hrms.backend.utils.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CONTROLLER = the HTTP "front door" of the AI assistant. It does no business logic: it reads the
 * request, finds out WHO is calling, and hands over to AiChatService.
 *
 * WHO is calling comes from {@code Authentication} (filled in by JwtAuthenticationFilter after it
 * verified the token), never from the request body.
 *
 *   POST   /ai/chat          {message, timeZone}                  -> GeminiChatResponse
 *   POST   /ai/confirm       {pendingActionId, confirm, timeZone} -> GeminiChatResponse
 *   GET    /ai/history                                            -> AiHistoryResponse
 *   DELETE /ai/conversation                                       -> 204 (clears the chat)
 */
@RestController
@RequestMapping("/ai")
public class GeminiController {

    private final AiChatService aiChatService;

    @Autowired
    public GeminiController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    @PostMapping("/chat")
    public ResponseEntity<GeminiChatResponse> chat(@Valid @RequestBody GeminiChatRequest request, Authentication authentication) {
        String userId = CurrentUser.id(authentication);
        return ResponseEntity.ok(aiChatService.chat(userId, request.message(), request.timeZone()));
    }

    @PostMapping("/confirm")
    public ResponseEntity<GeminiChatResponse> confirm(@Valid @RequestBody GeminiConfirmRequest request, Authentication authentication) {
        String userId = CurrentUser.id(authentication);
        return ResponseEntity.ok(aiChatService.confirm(userId, request.pendingActionId(), request.confirm(), request.timeZone()));
    }

    @GetMapping("/history")
    public ResponseEntity<AiHistoryResponse> history(Authentication authentication) {
        return ResponseEntity.ok(aiChatService.history(CurrentUser.id(authentication)));
    }

    @DeleteMapping("/conversation")
    public ResponseEntity<Void> clearConversation(Authentication authentication) {
        aiChatService.clearConversation(CurrentUser.id(authentication));
        return ResponseEntity.noContent().build();
    }
}
