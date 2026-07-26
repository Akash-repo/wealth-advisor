package com.wealth.advisor.agent;

import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.adk.sessions.SessionNotFoundException;
import com.google.cloud.firestore.Firestore;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
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
 * @see AgentConfig for how {@code wealthAdvisorPipeline} (the {@link SequentialAgent} this class
 *     runs) is assembled
 */
@Service
public class AgentChatService {

	private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

	private final Runner runner;

	public AgentChatService(SequentialAgent wealthAdvisorPipeline, Firestore firestore) {
		this.runner =
				Runner.builder()
						.agent(wealthAdvisorPipeline)
						.appName(wealthAdvisorPipeline.name())
						.sessionService(new FirestoreSessionService(firestore))
						.build();
		log.info(
				"AgentChatService ready: agent='{}', appName='{}'",
				wealthAdvisorPipeline.name(),
				runner.appName());
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
		// stock_recommendation's, all appended to the same session. Only the last agent's text
		// is meant for the user (the first agent's text is consumed via {portfolio_analysis} in
		// the second agent's instruction, not shown directly) — so we walk backwards and return
		// the last non-empty text found, rather than concatenating every event's text.
		String reply = "";
		for (int i = events.size() - 1; i >= 0; i--) {
			String text = textOf(events.get(i));
			if (!text.isEmpty()) {
				reply = text;
				break;
			}
		}

		log.info(
				"Turn complete for userId='{}': {} events, {} ms, replyLength={}",
				userId,
				events.size(),
				System.currentTimeMillis() - startedAt,
				reply.length());
		return reply;
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

	private static String textOf(Event event) {
		StringBuilder text = new StringBuilder();
		event.content().flatMap(Content::parts).stream()
				.flatMap(List::stream)
				.flatMap(part -> part.text().stream())
				.forEach(text::append);
		return text.toString();
	}
}
