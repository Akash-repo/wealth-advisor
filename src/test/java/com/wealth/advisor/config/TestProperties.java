package com.wealth.advisor.config;

/**
 * Explicit {@link AdvisorProperties} for unit tests that don't start Spring. The values mirror
 * {@code application.yml}; {@code AdvisorPropertiesTest} verifies the real file binds and
 * validates, so these two can't silently diverge in a way that matters.
 */
public final class TestProperties {

	private TestProperties() {}

	public static AdvisorProperties defaults() {
		return new AdvisorProperties(
				new AdvisorProperties.Model("gemini-2.5-flash", "gemini-2.5-flash"),
				new AdvisorProperties.Chat(20, 50, 60),
				new AdvisorProperties.Compaction(50_000, 20),
				new AdvisorProperties.Memory(8, 500),
				new AdvisorProperties.Guardrails(
						true,
						"gemini-2.5-flash-lite",
						true,
						4,
						"I can only help with investing, your portfolio and personal finance. Ask me about your holdings, a stock, or how to think about saving and allocation."));
	}
}
