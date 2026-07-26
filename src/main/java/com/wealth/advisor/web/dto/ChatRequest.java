package com.wealth.advisor.web.dto;

/** Request body for {@code POST /api/chat}. The caller's identity comes from their
 * authenticated session, not from this body — see {@link com.wealth.advisor.web.ChatController}. */
public record ChatRequest(String message) {
}
