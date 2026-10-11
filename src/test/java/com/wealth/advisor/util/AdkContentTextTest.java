package com.wealth.advisor.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdkContentTextTest {

	@Test
	void nullContent_isEmpty() {
		assertThat(AdkContentText.textOf((Content) null)).isEmpty();
	}

	@Test
	void contentWithNoParts_isEmpty() {
		assertThat(AdkContentText.textOf(Content.builder().role("model").build())).isEmpty();
	}

	@Test
	void textParts_areConcatenatedInOrder() {
		Content content = Content.fromParts(Part.fromText("Hello, "), Part.fromText("world"));

		assertThat(AdkContentText.textOf(content)).isEqualTo("Hello, world");
	}

	@Test
	void nonTextParts_areIgnored() {
		Content content =
				Content.fromParts(
						Part.fromText("before "),
						Part.fromFunctionCall("getStockData", Map.of("ticker", "NVDA")),
						Part.fromText("after"));

		assertThat(AdkContentText.textOf(content)).isEqualTo("before after");
	}

	@Test
	void event_usesItsContent() {
		Event event =
				Event.builder()
						.author("portfolio_analysis")
						.content(Content.fromParts(Part.fromText("analysis")))
						.build();

		assertThat(AdkContentText.textOf(event)).isEqualTo("analysis");
	}

	@Test
	void eventWithoutContent_isEmpty() {
		assertThat(AdkContentText.textOf(Event.builder().author("user").build())).isEmpty();
	}
}
