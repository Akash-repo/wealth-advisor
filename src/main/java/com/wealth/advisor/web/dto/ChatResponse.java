package com.wealth.advisor.web.dto;

/** Response body for {@code POST /api/chat} — just {@code {"reply": "..."}}. */
public record ChatResponse(String reply) {
}
