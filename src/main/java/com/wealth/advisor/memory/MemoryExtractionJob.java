package com.wealth.advisor.memory;

import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.constants.LogKeys;
import com.wealth.advisor.logging.LogContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The background job that turns a user's finished conversations into long-term memory. Started when
 * the user opens the chat page ({@code POST /api/memory/refresh}) and run off the request thread.
 *
 * <p>For each of the user's sessions that has <em>changed</em> since it was last processed and has
 * <em>settled</em> (no new message for a while, so a half-finished conversation is not read), the whole
 * session is read again and everything derived from it is recomputed. No bookmark inside a session and no
 * lease: the writes are idempotent (profile entries are upserted by key), so two overlapping runs only
 * repeat work. A session that fails is logged and retried on the next run. See
 * docs/design/03-user-preferences-memory.md.
 */
@Component
public class MemoryExtractionJob {

	private static final Logger log = LoggerFactory.getLogger(MemoryExtractionJob.class);

	private final SessionSource sessions;
	private final PreferenceExtractor preferenceExtractor;
	private final PreferenceReconciler reconciler;
	private final PreferenceStore preferences;
	private final ExtractionStateStore state;
	private final Clock clock;
	private final Duration settle;
	private final int maxSessionsPerRun;
	private final Set<String> running = ConcurrentHashMap.newKeySet();

	public MemoryExtractionJob(
			SessionSource sessions,
			PreferenceExtractor preferenceExtractor,
			PreferenceReconciler reconciler,
			PreferenceStore preferences,
			ExtractionStateStore state,
			Clock clock,
			AdvisorProperties properties) {
		this.sessions = sessions;
		this.preferenceExtractor = preferenceExtractor;
		this.reconciler = reconciler;
		this.preferences = preferences;
		this.state = state;
		this.clock = clock;
		this.settle = Duration.ofMinutes(properties.extraction().settleMinutes());
		this.maxSessionsPerRun = properties.extraction().maxSessionsPerRun();
	}

	/** Never throws: a failing run is logged and simply tried again on the next page load. */
	public void refresh(String userId) {
		if (!running.add(userId)) {
			log.debug("Memory refresh already running for this user; skipping");
			return;
		}
		try {
			processDueSessions(userId);
		} catch (RuntimeException e) {
			log.warn("Memory refresh failed for userId='{}'", userId, e);
		} finally {
			running.remove(userId);
		}
	}

	private void processDueSessions(String userId) {
		Map<String, Instant> done = state.processed(userId);
		Instant now = clock.instant();
		List<SessionSource.SessionStamp> due = sessions.list(userId).stream()
				.filter(s -> changedSince(done.get(s.id()), s) && settled(s, now))
				.sorted(Comparator.comparing(SessionSource.SessionStamp::lastUpdate))
				.limit(maxSessionsPerRun)
				.toList();
		log.debug("Memory refresh: {} session(s) due", due.size());
		for (SessionSource.SessionStamp stamp : due) {
			try (LogContext.Scope ignored = LogContext.with(Map.of(LogKeys.USER_ID, userId, LogKeys.SESSION_ID, stamp.id()))) {
				processSession(userId, stamp);
			} catch (RuntimeException e) {
				log.warn("Memory extraction failed for session '{}'; will retry on the next run", stamp.id(), e);
			}
		}
	}

	private void processSession(String userId, SessionSource.SessionStamp stamp) {
		List<TimedMessage> userMessages = sessions.messages(userId, stamp.id()).stream().filter(TimedMessage::isUser).toList();
		if (!userMessages.isEmpty()) {
			extractPreferences(userId, stamp.id(), userMessages);
		}
		state.markProcessed(userId, stamp.id(), stamp.lastUpdate());
	}

	private void extractPreferences(String userId, String sessionId, List<TimedMessage> userMessages) {
		List<PreferenceOperation> operations = preferenceExtractor.extract(preferences.load(userId), userMessages);
		if (operations.isEmpty()) {
			return;
		}
		preferences.update(userId, profile -> {
			PreferenceReconciler.Result result = reconciler.reconcile(profile, operations, userMessages, sessionId);
			log.info("Preferences: {} change(s) applied, {} dropped {}", result.applied(), result.dropped().size(), result.dropped());
			return result.profile();
		});
	}

	private static boolean changedSince(Instant processed, SessionSource.SessionStamp stamp) {
		return processed == null || stamp.lastUpdate().isAfter(processed);
	}

	private boolean settled(SessionSource.SessionStamp stamp, Instant now) {
		return Duration.between(stamp.lastUpdate(), now).compareTo(settle) >= 0;
	}
}
