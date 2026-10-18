package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptTemplateTest {

	@Test
	void fillsKnownPlaceholders() {
		assertThat(PromptTemplate.fill("a {x} b {y}", Map.of("x", "1", "y", "2"))).isEqualTo("a 1 b 2");
	}

	@Test
	void leavesUnknownPlaceholdersAlone() {
		assertThat(PromptTemplate.fill("a {x} {nope}", Map.of("x", "1"))).isEqualTo("a 1 {nope}");
	}

	@Test
	void substitutedTextIsNeverScannedAgain() {
		assertThat(PromptTemplate.fill("{a} then {b}", Map.of("a", "{b}", "b", "B"))).isEqualTo("{b} then B");
	}

	@Test
	void dollarSignsAndBackslashesInValuesAreLiteral() {
		assertThat(PromptTemplate.fill("{a}", Map.of("a", "cost $1 \\ end"))).isEqualTo("cost $1 \\ end");
	}
}
