package com.wealth.advisor.memory;

import java.time.Instant;
import java.util.List;

/**
 * What the memory job needs to know about a user's conversations, without any ADK types, so the job
 * can be tested with a plain fake. {@link AdkSessionSource} is the real one.
 */
public interface SessionSource {

	/** A conversation's id and when it last changed — cheap to list, no messages loaded. */
	record SessionStamp(String id, Instant lastUpdate) {}

	List<SessionStamp> list(String userId);

	/** The displayable messages of one conversation, in order, numbered from 1. */
	List<TimedMessage> messages(String userId, String sessionId);
}
