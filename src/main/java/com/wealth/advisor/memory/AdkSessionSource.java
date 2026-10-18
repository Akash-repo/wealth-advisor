package com.wealth.advisor.memory;

import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@link SessionSource} backed by the ADK session service the chat itself uses. */
@Component
public class AdkSessionSource implements SessionSource {

	private final Runner runner;
	private final ConversationReader reader;

	public AdkSessionSource(Runner runner, ConversationReader reader) {
		this.runner = runner;
		this.reader = reader;
	}

	/**
	 * {@code FirestoreSessionService.listSessions} returns every session with empty events and state,
	 * which is exactly what a cheap "what changed?" check needs.
	 */
	@Override
	public List<SessionStamp> list(String userId) {
		return runner.sessionService().listSessions(runner.appName(), userId).blockingGet().sessions().stream()
				.map(s -> new SessionStamp(s.id(), s.lastUpdateTime()))
				.toList();
	}

	@Override
	public List<TimedMessage> messages(String userId, String sessionId) {
		Session session = runner.sessionService()
				.getSession(runner.appName(), userId, sessionId, Optional.empty())
				.blockingGet();
		return session == null ? List.of() : reader.read(userId, session);
	}
}
