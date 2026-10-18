package com.wealth.advisor.memory;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.wealth.advisor.util.AdkContentText;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns an ADK {@link Session}'s raw events into the displayable conversation: the user's messages
 * and, per turn, only the final agent's reply — the intermediate {@code portfolio_analysis} text is
 * internal. Used for the chat history shown in the UI and by the memory job, so both see the same
 * conversation with the same message numbers.
 */
@Component
public class ConversationReader {

	private final String finalAgentName;

	public ConversationReader(String agent2Name) {
		this.finalAgentName = agent2Name;
	}

	public List<TimedMessage> read(String userId, Session session) {
		List<TimedMessage> messages = new ArrayList<>();
		boolean lastWasAgentReply = false;
		for (Event event : session.events()) {
			String text = AdkContentText.textOf(event);
			if (text.isEmpty()) {
				continue;
			}
			Instant at = Instant.ofEpochMilli(event.timestamp());
			// FirestoreSessionService stores the user's own events with author == userId, not the
			// literal "user" ADK uses in-memory — accept both.
			if ("user".equals(event.author()) || userId.equalsIgnoreCase(event.author())) {
				messages.add(new TimedMessage(0, TimedMessage.USER, at, text));
				lastWasAgentReply = false;
			} else if (finalAgentName.equals(event.author())) {
				// A later final-agent event in the same turn supersedes an earlier one.
				if (lastWasAgentReply) {
					messages.remove(messages.size() - 1);
				}
				messages.add(new TimedMessage(0, TimedMessage.AGENT, at, text));
				lastWasAgentReply = true;
			}
		}
		return numbered(messages);
	}

	private static List<TimedMessage> numbered(List<TimedMessage> messages) {
		List<TimedMessage> result = new ArrayList<>(messages.size());
		for (int i = 0; i < messages.size(); i++) {
			TimedMessage m = messages.get(i);
			result.add(new TimedMessage(i + 1, m.role(), m.at(), m.text()));
		}
		return result;
	}
}
