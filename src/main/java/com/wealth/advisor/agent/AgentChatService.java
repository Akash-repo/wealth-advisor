package com.wealth.advisor.agent;

import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.adk.sessions.SessionNotFoundException;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.wealth.advisor.audit.AgentAuditRecorder;
import com.wealth.advisor.audit.ToolUsageRecorder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>Conversation history is persisted in Firestore via {@link FirestoreSessionService} (not
 * in-memory anymore), one session per user, with the session id simply set equal to the user id
 * — this app only ever has one ongoing conversation per user, so there's no need to juggle
 * multiple session ids.
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
 * AgentAuditRecorder}, and every real tool call by {@link ToolUsageRecorder} (both wired onto both
 * agents in {@code AgentConfig}) — this class only needs to call their respective {@code
 * finalizeTurn} methods once the turn is over (success or failure) so that accumulated data gets
 * written; for {@code AgentAuditRecorder} that also attaches the per-agent hand-off ({@code
 * input_to_agent2}) and final reply to the same row.
 *
 * @see AgentConfig for how the {@code SequentialAgent} pipeline, the {@link Runner} bean, and the
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

	public AgentChatService(
			Runner runner,
			String agent1Name,
			String agent2Name,
			AgentAuditRecorder agentAuditRecorder,
			ToolUsageRecorder toolUsageRecorder) {
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
	 * Runs one turn of conversation for {@code userId} and returns the reply to show the user.
	 *
	 * @param userId the authenticated user's id; doubles as the ADK session id (see class javadoc)
	 * @param message the user's natural-language message for this turn
	 * @return the text of the final agent's reply for this turn
	 */
	public String chat(String userId, String message) {
		long startedAt = System.currentTimeMillis();
		ensureSessionExists(userId);

		log.debug("Running turn for userId='{}': \"{}\"", userId, message);

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
							.runAsync(userId, userId, Content.fromParts(Part.fromText(message)))
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
			log.error("Turn failed for userId='{}'", userId, e);
			throw e;
		} finally {
			long totalLatencyMs = System.currentTimeMillis() - startedAt;
			agentAuditRecorder.finalizeTurn(
					userId, agent1Name, agent2Name, inputToAgent2, reply, totalLatencyMs, status, errorMessage);
			toolUsageRecorder.finalizeTurn(userId);
			log.info(
					"Turn complete for userId='{}': {} ms, status={}, replyLength={}",
					userId,
					totalLatencyMs,
					status,
					reply.length());
		}
	}

	private void ensureSessionExists(String userId) {
		try {
			runner
					.sessionService()
					.getSession(runner.appName(), userId, userId, Optional.empty())
					.blockingGet();
			log.debug("Reusing existing Firestore session for userId='{}'", userId);
		} catch (SessionNotFoundException e) {
			log.info("No session found for userId='{}' yet — creating one", userId);
			runner
					.sessionService()
					.createSession(runner.appName(), userId, (Map<String, Object>) null, userId)
					.blockingGet();
		}
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
			String text = textOf(event);
			if (!text.isEmpty()) {
				return text;
			}
		}
		return "";
	}

	private static String textOf(Event event) {
		StringBuilder text = new StringBuilder();
		event.content().flatMap(Content::parts).stream()
				.flatMap(List::stream)
				.flatMap(part -> part.text().stream())
				.forEach(text::append);
		return text.toString();
	}
}
