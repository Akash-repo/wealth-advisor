package com.wealth.advisor.web.dto;

/**
 * Response body for {@code POST /api/chat}: the agent's reply plus the session it ran in, which is
 * how a client that started a new conversation learns the id to send on its next message.
 */
public record ChatResponse(String sessionId, String reply) {
}
