package com.wealth.advisor.util;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import java.util.List;

/**
 * Pulls the plain text out of ADK's {@link Content} / {@link Event}, which model a message as a
 * list of typed parts (text, function call, function response, file...). Only text parts count;
 * everything else is ignored, and text parts are concatenated in order.
 *
 * <p>Stateless and dependency-free on purpose — this was previously written twice (once in the
 * chat service, once in the audit recorder), which is exactly how two copies drift apart.
 */
public final class AdkContentText {

	private AdkContentText() {}

	/** The concatenated text parts of {@code content}; empty if {@code content} is null or has none. */
	public static String textOf(Content content) {
		if (content == null) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		content.parts().stream()
				.flatMap(List::stream)
				.forEach(part -> part.text().ifPresent(text::append));
		return text.toString();
	}

	/** The concatenated text parts of {@code event}'s content; empty if it has no content. */
	public static String textOf(Event event) {
		return event.content().map(AdkContentText::textOf).orElse("");
	}
}
