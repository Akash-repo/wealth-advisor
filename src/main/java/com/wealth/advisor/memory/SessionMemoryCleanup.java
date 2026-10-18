package com.wealth.advisor.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Forgets what was derived from a conversation when the conversation is deleted: its episodes and its
 * "already processed" record. The user's preference profile is deliberately kept: it describes the user,
 * not one conversation, and the user manages it in the preferences panel.
 */
@Component
public class SessionMemoryCleanup {

	private static final Logger log = LoggerFactory.getLogger(SessionMemoryCleanup.class);

	private final EpisodeStore episodes;
	private final ExtractionStateStore state;

	public SessionMemoryCleanup(EpisodeStore episodes, ExtractionStateStore state) {
		this.episodes = episodes;
		this.state = state;
	}

	/** Never throws: a leftover record is harmless and the session itself is already gone. */
	public void forgetSession(String userId, String sessionId) {
		try {
			episodes.deleteSession(userId, sessionId);
			state.delete(userId, sessionId);
		} catch (RuntimeException e) {
			log.warn("Could not clean up memory for deleted session '{}'", sessionId, e);
		}
	}
}
