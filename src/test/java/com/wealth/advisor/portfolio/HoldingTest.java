package com.wealth.advisor.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HoldingTest {

	@Test
	void ticker_isTrimmedAndUpperCased() {
		assertThat(new Holding("  googl ", 10.0, null, null).ticker()).isEqualTo("GOOGL");
	}

	@Test
	void holdingWithSymbolAndShares_isUsable() {
		assertThat(new Holding("AAPL", 5.0, 120.5, "Fidelity").isUsable()).isTrue();
	}

	@Test
	void missingOrBlankSymbol_isNotUsable() {
		assertThat(new Holding(null, 5.0, null, null).isUsable()).isFalse();
		assertThat(new Holding("  ", 5.0, null, null).isUsable()).isFalse();
	}

	@Test
	void missingShares_isNotUsable() {
		assertThat(new Holding("AAPL", null, null, null).isUsable()).isFalse();
	}
}
