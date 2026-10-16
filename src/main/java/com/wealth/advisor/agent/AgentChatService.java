package com.wealth.advisor.agent;

import com.wealth.advisor.constants.LogKeys;
import com.wealth.advisor.constants.SessionStateKeys;
import com.wealth.advisor.logging.LogContext;
import com.wealth.advisor.util.AdkContentText;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionNotFoundException;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.audit.AgentAuditRecorder;
import com.wealth.advisor.audit.ToolUsageRecorder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Bridges Spring's request/response world with ADK's session + event model.
 *
 * <p>ADK's core execution unit is the {@code Runner}: you hand it a user id, a session id, and
 * a new {@link Content} message, and it returns a stream ({@code Flowable}) of {@link Event}s —
 * one event per step any agent in the pipeline takes (a tool call, a tool response, an agent's
 * text reply, etc). For a simple request/response chat endpoint we don't need streaming, so we
 * collect the whole event stream for the turn and pick out the reply to show the user:
 *
 * <pre>{@code
 * // Minimal shape of what this class does, without the session bookkeeping:
 * Flowable<Event> events = runner.runAsync(userId, sessionId, Content.fromParts(Part.fromText("hi")));
 * List<Event> all = events.toList().blockingGet();  // wait for the whole turn
 * }</pre>
 *
 * <p>Conversation history is persisted in Firestore via {@link FirestoreSessionService}. A user
 * can have many sessions (conversations); each is keyed by a server-generated UUID. Ownership is
 * enforced by ADK's own (app, userId, sessionId) key — a session id from another user simply isn't
 * found, which we surface as a 404. Sessions created before multi-session support used the user id
 * as the session id; they still resolve like any other session and show up in the list.
 *
 * <p>{@code Runner}'s built-in {@code RunConfig.autoCreateSession} doesn't actually work with
 * {@code FirestoreSessionService}: {@code Runner.runAsync} only auto-creates a session when the
 * session lookup completes empty, but {@code FirestoreSessionService.getSession} signals a
 * missing session by erroring with {@link SessionNotFoundException} instead — a real
 * inconsistency between that session service and what {@code Runner} expects (verified by
 * hitting it live, not documented anywhere). So {@link #ensureSessionExists} does the
 * get-or-create check itself before every turn instead of relying on that flag.
 *
 * <p>Every real Gemini call either agent makes is separately recorded to BigQuery by {@link
 * AgentAuditRecorder}, and every real tool call by {@link ToolUsageRecorder} (both are ADK global
 * plugins, registered once on the {@code App} in {@code RunnerConfig}) — this class only needs to
 * call their respective {@code finalizeTurn} methods once the turn is over (success or failure) so that accumulated data gets
 * written; for {@code AgentAuditRecorder} that also attaches the per-agent hand-off ({@code
 * input_to_agent2}) and final reply to the same row.
 *
 * @see com.wealth.advisor.config.AgentConfig for how the {@code SequentialAgent} pipeline, the {@link Runner} bean, and the
 *     {@code agent1Name}/{@code agent2Name} beans this class is handed are all assembled — every
 *     ADK object this app builds lives there, this class only consumes the finished results
 */
@Service
public class AgentChatService {

	private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

	private final Runner runner;
	private final String agent1Name;
	private final String agent2Name;
	private final AgentAuditRecorder agentAuditRecorder;
	private final ToolUsageRecorder toolUsageRecorder;
	private final int maxTitleLength;
	private final int maxListed;
	// (userId, sessionId) pairs with a turn running right now. ADK doesn't serialize turns within
	// one session, and two interleaved turns would corrupt that session's history and the audit
	// correlation, so a second concurrent message to the same session is rejected with 409.
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

	public AgentChatService(
			Runner runner,
			String agent1Name,
			String agent2Name,
			AgentAuditRecorder agentAuditRecorder,
			ToolUsageRecorder toolUsageRecorder,
			AdvisorProperties properties) {
		this.maxTitleLength = properties.chat().maxTitleLength();
		this.maxListed = properties.chat().maxListedSessions();
		this.runner = runner;
		this.agent1Name = agent1Name;
		this.agent2Name = agent2Name;
		this.agentAuditRecorder = agentAuditRecorder;
		this.toolUsageRecorder = toolUsageRecorder;
		log.info(
				"AgentChatService ready: appName='{}', pipeline={} -> {}",
				runner.appName(),
				agent1Name,
				agent2Name);
	}

	/**
	 * Runs one turn of conversation and returns the reply to show the user.
	 *
	 * @param userId the authenticated user's id
	 * @param sessionId the conversation to continue, or {@code null}/blank to start a new one
	 * @param message the user's natural-language message for this turn
	 * @throws ChatSessionNotFoundException if {@code sessionId} isn't one of this user's sessions
	 * @throws ChatSessionBusyException if a turn is already running in that session
	 */
	public ChatResult chat(String userId, String sessionId, String message) {
		boolean isNew = sessionId == null || sessionId.isBlank();
		String sid = isNew ? createSession(userId, titleFrom(message)) : requireOwnedSession(userId, sessionId).id();

		String key = userId + "/" + sid;
		if (!inFlight.add(key)) {
			throw new ChatSessionBusyException(sid);
		}
		// Every line logged during this turn on a thread we wrap carries the user and the session.
		try (LogContext.Scope ignored = LogContext.with(Map.of(LogKeys.USER_ID, userId, LogKeys.SESSION_ID, sid))) {
			return new ChatResult(sid, runTurn(userId, sid, message));
		} catch (RuntimeException e) {
			// A brand-new conversation whose very first turn failed would otherwise linger in the
			// list as an empty ghost the user never saw a reply in.
			if (isNew) {
				try {
					runner.sessionService().deleteSession(runner.appName(), userId, sid).blockingAwait();
				} catch (RuntimeException cleanupFailure) {
					log.warn("Could not clean up empty session '{}' after a failed first turn", sid, cleanupFailure);
				}
			}
			throw e;
		} finally {
			inFlight.remove(key);
		}
	}

	private String runTurn(String userId, String sessionId, String message) {
		long startedAt = System.currentTimeMillis();
		log.debug("Running turn for userId='{}' sessionId='{}': \"{}\"", userId, sessionId, message);

		String reply = "";
		String inputToAgent2 = "";
		String status = "SUCCESS";
		String errorMessage = null;
		try {
			// runAsync returns a Flowable (RxJava) of Events; blockingGet() waits for the whole
			// turn to finish and collects every event into a List, since we just want a plain
			// String reply here rather than a live stream.
			List<Event> events =
					runner
							.runAsync(userId, sessionId, Content.fromParts(Part.fromText(message)))
							.toList()
							.blockingGet();

			// A turn runs both agents in wealthAdvisorPipeline in sequence, so this event list
			// contains portfolio_analysis's tool-call/response/text events *and*
			// stock_recommendation's, all appended to the same session. Only the last agent's
			// text is meant for the user (the first agent's text is consumed via
			// {portfolio_analysis} in the second agent's instruction, not shown directly) — so we
			// walk backwards and return the last non-empty text found, rather than concatenating
			// every event's text.
			reply = lastNonEmptyTextFrom(events, null);
			// For the audit row: specifically agent1's contribution, not just "the last text
			// before agent2's" — filtering by author is the precise way to get it.
			inputToAgent2 = lastNonEmptyTextFrom(events, agent1Name);
			return reply;
		} catch (RuntimeException e) {
			status = "ERROR";
			errorMessage = e.getMessage();
			log.error("Turn failed for userId='{}' sessionId='{}'", userId, sessionId, e);
			throw e;
		} finally {
			long totalLatencyMs = System.currentTimeMillis() - startedAt;
			agentAuditRecorder.finalizeTurn(
					userId, sessionId, agent1Name, agent2Name, inputToAgent2, reply, totalLatencyMs, status, errorMessage);
			toolUsageRecorder.finalizeTurn(userId, sessionId);
			log.info(
					"Turn complete for userId='{}' sessionId='{}': {} ms, status={}, replyLength={}",
					userId,
					sessionId,
					totalLatencyMs,
					status,
					reply.length());
		}
	}

	/**
	 * The user's conversations, most recently active first. {@code FirestoreSessionService.listSessions}
	 * deliberately returns every session with <em>empty</em> state and events, so the title (kept in
	 * session state) needs a per-session {@code getSession}. Bounded to the {@code advisor.chat.max-listed-sessions}
	 * most recent sessions, and each fetch asks for a single event, to keep that N+1 cheap.
	 */
	public List<SessionSummary> listSessions(String userId) {
		List<Session> recent =
				runner.sessionService().listSessions(runner.appName(), userId).blockingGet().sessions().stream()
						.sorted(
								Comparator.comparing(
												(Session x) -> x.lastUpdateTime(), Comparator.nullsFirst(Comparator.naturalOrder()))
										.reversed())
						.limit(maxListed)
						.toList();
		GetSessionConfig titleOnly = GetSessionConfig.builder().numRecentEvents(1).build();
		List<SessionSummary> summaries = new ArrayList<>();
		for (Session s : recent) {
			String title = "Conversation";
			try {
				Session full =
						runner
								.sessionService()
								.getSession(runner.appName(), userId, s.id(), Optional.of(titleOnly))
								.blockingGet();
				if (full != null && full.state().get(SessionStateKeys.TITLE) instanceof String t) {
					title = t;
				}
			} catch (RuntimeException e) {
				log.debug("Could not load title for session '{}': {}", s.id(), e.toString());
			}
			summaries.add(new SessionSummary(s.id(), title, s.lastUpdateTime()));
		}
		return summaries;
	}

	/**
	 * What to redraw when the user reopens a conversation: their messages and, per turn, only the
	 * final agent's reply — the intermediate {@code portfolio_analysis} text is internal, same
	 * rule {@link #runTurn} applies when picking a reply.
	 */
	public List<ChatMessage> history(String userId, String sessionId) {
		Session session = requireOwnedSession(userId, sessionId);
		List<ChatMessage> messages = new ArrayList<>();
		boolean lastWasAgentReply = false;
		for (Event event : session.events()) {
			String text = AdkContentText.textOf(event);
			if (text.isEmpty()) {
				continue;
			}
			// FirestoreSessionService stores the user's own events with author == userId, not the
			// literal "user" ADK uses in-memory — accept both.
			if ("user".equals(event.author()) || userId.equalsIgnoreCase(event.author())) {
				messages.add(new ChatMessage("user", text));
				lastWasAgentReply = false;
			} else if (agent2Name.equals(event.author())) {
				// A later final-agent event in the same turn supersedes an earlier one.
				if (lastWasAgentReply) {
					messages.remove(messages.size() - 1);
				}
				messages.add(new ChatMessage("agent", text));
				lastWasAgentReply = true;
			}
		}
		return messages;
	}

	public void deleteSession(String userId, String sessionId) {
		requireOwnedSession(userId, sessionId);
		runner.sessionService().deleteSession(runner.appName(), userId, sessionId).blockingAwait();
	}


	private String createSession(String userId, String title) {
		String sessionId = UUID.randomUUID().toString();
		runner
				.sessionService()
				.createSession(runner.appName(), userId, new ConcurrentHashMap<>(Map.of(SessionStateKeys.TITLE, title)), sessionId)
				.blockingGet();
		log.info("Created session '{}' for userId='{}'", sessionId, userId);
		return sessionId;
	}

	/**
	 * Loads the session only if it belongs to {@code userId}. {@code FirestoreSessionService} signals
	 * "missing" by throwing {@link SessionNotFoundException} while other implementations complete
	 * empty — both are handled, and both mean "not yours or doesn't exist" (same 404).
	 */
	private Session requireOwnedSession(String userId, String sessionId) {
		try {
			Session session =
					runner
							.sessionService()
							.getSession(runner.appName(), userId, sessionId, Optional.empty())
							.blockingGet();
			if (session == null) {
				throw new ChatSessionNotFoundException(sessionId);
			}
			return session;
		} catch (SessionNotFoundException e) {
			throw new ChatSessionNotFoundException(sessionId);
		}
	}

	private String titleFrom(String message) {
		String oneLine = message.strip().replaceAll("\\s+", " ");
		return oneLine.length() <= maxTitleLength ? oneLine : oneLine.substring(0, maxTitleLength - 1) + "…";
	}

	/**
	 * Walks {@code events} backwards and returns the last non-empty text found, optionally
	 * restricted to events authored by {@code authorFilter} (pass {@code null} for any author).
	 */
	private static String lastNonEmptyTextFrom(List<Event> events, String authorFilter) {
		for (int i = events.size() - 1; i >= 0; i--) {
			Event event = events.get(i);
			if (authorFilter != null && !authorFilter.equals(event.author())) {
				continue;
			}
			String text = AdkContentText.textOf(event);
			if (!text.isEmpty()) {
				return text;
			}
		}
		return "";
	}

}
