package com.wealth.advisor.web.dto;

/**
 * Request body for {@code POST /api/chat} — just {@code {"message": "..."}}. Deliberately has no
 * {@code userId} field: the caller's identity comes from their authenticated session instead, see
 * {@link com.wealth.advisor.web.ChatController}.
 */
public record ChatRequest(String message) {
}
