package com.wealth.advisor.memory;

import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.wealth.advisor.config.AgentConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link SessionSource} backed by the ADK session service the chat itself uses. It depends on the session
 * service and the application name rather than on the {@code Runner}: the runner depends on the agents,
 * and one of the agents' tools reads sessions through this class, so depending on the runner would be a
 * circular dependency.
 */
@Component
public class AdkSessionSource implements SessionSource {

	private final BaseSessionService sessionService;
	private final ConversationReader reader;

	public AdkSessionSource(BaseSessionService sessionService, ConversationReader reader) {
		this.sessionService = sessionService;
		this.reader = reader;
	}

	/**
	 * {@code FirestoreSessionService.listSessions} returns every session with empty events and state,
	 * which is exactly what a cheap "what changed?" check needs.
	 */
	@Override
	public List<SessionStamp> list(String userId) {
		return sessionService.listSessions(AgentConfig.PIPELINE_NAME, userId).blockingGet().sessions().stream()
				.map(s -> new SessionStamp(s.id(), s.lastUpdateTime()))
				.toList();
	}

	@Override
	public List<TimedMessage> messages(String userId, String sessionId) {
		Session session = sessionService.getSession(AgentConfig.PIPELINE_NAME, userId, sessionId, Optional.empty()).blockingGet();
		return session == null ? List.of() : reader.read(userId, session);
	}
}
