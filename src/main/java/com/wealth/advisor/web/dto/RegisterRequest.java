package com.wealth.advisor.web.dto;

/** Request body for {@code POST /api/auth/register}. */
public record RegisterRequest(String username, String email, String password) {
}
