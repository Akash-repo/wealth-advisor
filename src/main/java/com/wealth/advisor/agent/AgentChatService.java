package com.wealth.advisor.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Bridges Spring's request/response world with ADK's session + event model.
 *
 * <p>ADK's core execution unit is the {@code Runner}: you hand it a user id, a session id, and
 * a new {@link Content} message, and it returns a stream ({@code Flowable}) of {@link Event}s —
 * one event per step the agent takes (a tool call, a tool response, the model's text reply,
 * etc). For a simple request/response chat endpoint we don't need streaming, so we collect the
 * whole event stream and concatenate the text parts.
 *
 * <p>Session state (conversation history so far) lives in the {@code SessionService}, not in
 * this class. Here we use {@link InMemoryRunner}, which wires up an in-memory session store —
 * fine for local learning/dev, but sessions vanish on restart and aren't shared across instances.
 * Milestone 4 swaps this for {@code FirestoreSessionService} so sessions survive restarts and
 * work multi-instance, at which point the per-user session cache below goes away entirely (the
 * session id would instead come from the authenticated HTTP session/JSESSIONID).
 */
@Service
public class AgentChatService {

	private final InMemoryRunner runner;

	// Maps our own userId -> the one ADK Session we've created for them, so repeated calls
	// continue the same conversation instead of starting a fresh one each time. This is a
	// stand-in for real session management until Spring Security + Firestore sessions land.
	private final Map<String, Session> sessionsByUser = new ConcurrentHashMap<>();

	public AgentChatService(LlmAgent portfolioAdvisorAgent) {
		// appName defaults to the agent's own name when not given explicitly.
		this.runner = new InMemoryRunner(portfolioAdvisorAgent);
	}

	/**
	 * Runs one turn of conversation for {@code userId} and returns the agent's final text reply.
	 *
	 * @param userId caller-supplied user identifier; used only to look up/create an ADK session,
	 *     not yet backed by real authentication (see {@link AgentChatService class javadoc})
	 * @param message the user's natural-language message for this turn
	 * @return the concatenated text of the agent's reply for this turn
	 */
	public String chat(String userId, String message) {
		Session session = sessionsByUser.computeIfAbsent(userId, this::createSession);

		// runAsync returns a Flowable (RxJava) of Events; blockingGet() waits for the whole
		// turn to finish and collects every event into a List, since we just want a plain
		// String reply here rather than a live stream.
		List<Event> events =
				runner
						.runAsync(userId, session.id(), Content.fromParts(Part.fromText(message)))
						.toList()
						.blockingGet();

		// A turn can contain multiple events (e.g. a tool-call event, a tool-response event,
		// then the model's text event). Only the text-bearing parts matter for a plain chat
		// reply, so we walk every event's parts and keep whatever has text.
		StringBuilder reply = new StringBuilder();
		for (Event event : events) {
			event.content().flatMap(Content::parts).stream()
					.flatMap(List::stream)
					.flatMap(part -> part.text().stream())
					.forEach(reply::append);
		}
		return reply.toString();
	}

	private Session createSession(String userId) {
		return runner.sessionService().createSession(runner.appName(), userId).blockingGet();
	}
}
