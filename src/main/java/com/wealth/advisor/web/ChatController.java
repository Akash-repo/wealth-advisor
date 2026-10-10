package com.wealth.advisor.web;

import com.wealth.advisor.agent.AgentChatService;
import com.wealth.advisor.agent.ChatMessage;
import com.wealth.advisor.agent.ChatResult;
import com.wealth.advisor.agent.SessionSummary;
import com.wealth.advisor.web.dto.ChatRequest;
import com.wealth.advisor.web.dto.ChatResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Entry point for talking to the portfolio advisor agent.
 *
 * <p>{@code userId} is taken from the authenticated {@link Authentication} principal (set by
 * {@link com.wealth.advisor.security.SecurityConfig}'s form login) rather than trusting a
 * client-supplied id. A user owns many conversations (ADK sessions); the client names one by
 * {@code sessionId}, but ADK keys sessions by (user, session), so another user's id is just a 404
 * — that's the multi-tenant isolation (FR-1). See {@link AgentChatService#chat} for what actually
 * happens with the message.
 *
 * <p>{@code @Valid} on the request body enforces {@link ChatRequest}'s size constraint (a
 * malformed/oversized request fails with Spring's default 400 response before this method body
 * ever runs); {@link ChatRateLimiter} caps how many turns one user can send per minute — see
 * {@code Optimization.md} P1-4/P1-3 for why both were missing before and what they protect
 * against (unbounded per-turn Gemini cost, permanently baked into a session that never resets).
 *
 * <pre>{@code
 * POST /api/chat                      { "sessionId": null | "<id>", "message": "..." }
 *   -> { "sessionId": "<id>", "reply": "..." }     (null sessionId starts a new conversation)
 * GET    /api/sessions                -> [ { "id", "title", "lastUpdated" } ]
 * GET    /api/sessions/{id}/messages  -> [ { "role": "user"|"agent", "text" } ]
 * DELETE /api/sessions/{id}           -> 204
 * }</pre>
 */
@RestController
public class ChatController {

	private static final Logger log = LoggerFactory.getLogger(ChatController.class);

	private final AgentChatService agentChatService;
	private final ChatRateLimiter chatRateLimiter;

	public ChatController(AgentChatService agentChatService, ChatRateLimiter chatRateLimiter) {
		this.agentChatService = agentChatService;
		this.chatRateLimiter = chatRateLimiter;
	}

	@PostMapping("/api/chat")
	public ChatResponse chat(@Valid @RequestBody ChatRequest request, Authentication authentication) {
		String userId = authentication.getName();
		if (!chatRateLimiter.tryAcquire(userId)) {
			log.warn("Rate limit exceeded for userId='{}'", userId);
			throw new ResponseStatusException(
					HttpStatus.TOO_MANY_REQUESTS, "Too many messages — please slow down and try again shortly.");
		}
		log.info("POST /api/chat for userId='{}' sessionId='{}'", userId, request.sessionId());
		ChatResult result = agentChatService.chat(userId, request.sessionId(), request.message());
		return new ChatResponse(result.sessionId(), result.reply());
	}

	@GetMapping("/api/sessions")
	public List<SessionSummary> sessions(Authentication authentication) {
		return agentChatService.listSessions(authentication.getName());
	}

	@GetMapping("/api/sessions/{sessionId}/messages")
	public List<ChatMessage> messages(@PathVariable String sessionId, Authentication authentication) {
		return agentChatService.history(authentication.getName(), sessionId);
	}

	@DeleteMapping("/api/sessions/{sessionId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteSession(@PathVariable String sessionId, Authentication authentication) {
		agentChatService.deleteSession(authentication.getName(), sessionId);
	}
}
