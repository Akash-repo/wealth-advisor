package com.wealth.advisor.web;

import com.wealth.advisor.agent.AgentChatService;
import com.wealth.advisor.web.dto.ChatRequest;
import com.wealth.advisor.web.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point for talking to the portfolio advisor agent.
 *
 * <p>{@code userId} is taken from the authenticated {@link Authentication} principal (set by
 * {@link com.wealth.advisor.security.SecurityConfig}'s form login) rather than trusting a
 * client-supplied id — this is what gives each logged-in user their own isolated ADK
 * conversation session (FR-1's multi-tenant isolation requirement). See {@link
 * AgentChatService#chat} for what actually happens with the message.
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

	public ChatController(AgentChatService agentChatService) {
		this.agentChatService = agentChatService;
	}

	@PostMapping("/api/chat")
	public ChatResponse chat(@RequestBody ChatRequest request, Authentication authentication) {
		String userId = authentication.getName();
		log.info("POST /api/chat for userId='{}'", userId);
		String reply = agentChatService.chat(userId, request.message());
		return new ChatResponse(reply);
	}
}
