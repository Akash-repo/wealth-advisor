package com.wealth.advisor.web;

import com.wealth.advisor.agent.AgentChatService;
import com.wealth.advisor.web.dto.ChatRequest;
import com.wealth.advisor.web.dto.ChatResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry point for talking to the portfolio advisor agent.
 *
 * <p>{@code userId} is now taken from the authenticated {@link Authentication} principal (set by
 * {@link com.wealth.advisor.security.SecurityConfig}'s form login) rather than trusting a
 * client-supplied id — this is what gives each logged-in user their own isolated ADK
 * conversation session (FR-1's multi-tenant isolation requirement).
 */
@RestController
public class ChatController {

	private final AgentChatService agentChatService;

	public ChatController(AgentChatService agentChatService) {
		this.agentChatService = agentChatService;
	}

	@PostMapping("/api/chat")
	public ChatResponse chat(@RequestBody ChatRequest request, Authentication authentication) {
		String reply = agentChatService.chat(authentication.getName(), request.message());
		return new ChatResponse(reply);
	}
}
