package com.wealth.advisor.memory;

import java.time.Instant;

/**
 * One message of a conversation as the memory job sees it. {@code index} numbers the messages of a
 * session from 1 in order; it is what the extractors refer to and what an episode's message ranges
 * point at. {@code role} is {@code "user"} or {@code "agent"}, the same words the chat history uses.
 */
public record TimedMessage(int index, String role, Instant at, String text) {

	public static final String USER = "user";
	public static final String AGENT = "agent";

	public boolean isUser() {
		return USER.equals(role);
	}
}
