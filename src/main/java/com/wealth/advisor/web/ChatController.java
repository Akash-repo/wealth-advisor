package com.wealth.advisor.web;

import com.wealth.advisor.agent.AgentChatService;
import com.wealth.advisor.web.dto.ChatRequest;
import com.wealth.advisor.web.dto.ChatResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Entry point for talking to the portfolio advisor agent.
 *
 * <p>{@code userId} is taken from the authenticated {@link Authentication} principal (set by
 * {@link com.wealth.advisor.security.SecurityConfig}'s form login) rather than trusting a
 * client-supplied id — this is what gives each logged-in user their own isolated ADK
 * conversation session (FR-1's multi-tenant isolation requirement). See {@link
 * AgentChatService#chat} for what actually happens with the message.
 *
 * <p>{@code @Valid} on the request body enforces {@link ChatRequest}'s size constraint (a
 * malformed/oversized request fails with Spring's default 400 response before this method body
 * ever runs); {@link ChatRateLimiter} caps how many turns one user can send per minute — see
 * {@code Optimization.md} P1-4/P1-3 for why both were missing before and what they protect
 * against (unbounded per-turn Gemini cost, permanently baked into a session that never resets).
 *
 * <pre>{@code
 * POST /api/chat
 * { "message": "Can I invest in Nvidia?" }
 *
 * 200 OK
 * { "reply": "..." }
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
		log.info("POST /api/chat for userId='{}'", userId);
		String reply = agentChatService.chat(userId, request.message());
		return new ChatResponse(reply);
	}
}
