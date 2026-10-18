package com.wealth.advisor.agent;

/** One displayable message in a conversation's history; {@code role} is "user" or "agent". */
public record ChatMessage(String role, String text) {}
