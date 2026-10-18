package com.wealth.advisor.memory;

/**
 * The typed slots of a user's preference profile. Typed (not a bag of text) so code can validate,
 * compare, and — for the exclusions — one day enforce them. {@link #OTHER} holds anything durable that
 * fits none of the slots, such as a theme exclusion (tobacco, weapons) that is not a GICS category.
 */
public enum PreferenceCategory {
	EXCLUDED_SECTORS("Excluded sectors", true),
	EXCLUDED_INDUSTRY_GROUPS("Excluded industry groups", true),
	EXCLUDED_TICKERS("Excluded tickers", true),
	GOALS("Goals and horizon", false),
	RISK_STANCE("Risk stance", false),
	STYLE("Style", false),
	OTHER("Other", false);

	private final String title;
	private final boolean exclusion;

	PreferenceCategory(String title, boolean exclusion) {
		this.title = title;
		this.exclusion = exclusion;
	}

	/** Heading shown to the user and in prompts. */
	public String title() {
		return title;
	}

	/** Exclusions are the facts that must never be trimmed from the prompt block. */
	public boolean isExclusion() {
		return exclusion;
	}
}
