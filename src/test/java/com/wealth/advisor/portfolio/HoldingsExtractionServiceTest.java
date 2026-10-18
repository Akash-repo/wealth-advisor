package com.wealth.advisor.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.wealth.advisor.agent.prompt.PromptLoader;
import com.wealth.advisor.config.TestProperties;
import org.junit.jupiter.api.Test;

/** The parsing half of extraction; the Gemini call itself is exercised live, not mocked. */
class HoldingsExtractionServiceTest {

	private final HoldingsExtractionService service =
			new HoldingsExtractionService(
					mock(Client.class), TestProperties.defaults(), new ObjectMapper(), new PromptLoader());

	@Test
	void parsesHoldingsIncludingOptionalFields() {
		var holdings =
				service.parse(
						"""
						[{"ticker":"aapl","shares":10,"costBasis":150.5,"account":"Fidelity"},
						 {"ticker":"MSFT","shares":5.5,"costBasis":null,"account":null}]""");

		assertThat(holdings).containsExactly(
				new Holding("AAPL", 10.0, 150.5, "Fidelity"), new Holding("MSFT", 5.5, null, null));
	}

	@Test
	void dropsMalformedEntries() {
		var holdings = service.parse("[{\"ticker\":\"\",\"shares\":3},{\"ticker\":\"KO\",\"shares\":2}]");

		assertThat(holdings).extracting(Holding::ticker).containsExactly("KO");
	}

	@Test
	void emptyArray_meansNoHoldingsFound() {
		assertThat(service.parse("[]")).isEmpty();
	}

	@Test
	void unparseableOutput_isAFailureNotAnEmptyResult() {
		assertThatThrownBy(() -> service.parse("this is not json"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("unparseable");
	}

	@Test
	void nullOutput_isAFailureNotAnEmptyResult() {
		assertThatThrownBy(() -> service.parse(null)).isInstanceOf(IllegalStateException.class);
	}
}
