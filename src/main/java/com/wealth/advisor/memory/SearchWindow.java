package com.wealth.advisor.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * How far back an episode search looks. Rolling windows, not calendar weeks: "last week" is ambiguous,
 * and since the window only narrows the candidates (the score decides relevance) a slightly wide one
 * is harmless. The model picks one of these words; code turns it into a start time, so the model never
 * does date arithmetic.
 */
public enum SearchWindow {
	PAST_WEEK(7),
	PAST_MONTH(30),
	PAST_3_MONTHS(90),
	PAST_YEAR(365),
	/** No limit from the caller; the configured default window applies. */
	ANY(0);

	private final int days;

	SearchWindow(int days) {
		this.days = days;
	}

	/** The earliest time covered; {@code defaultDays} is what {@link #ANY} means. */
	public Instant since(Instant now, int defaultDays) {
		return now.minus(Duration.ofDays(days == 0 ? defaultDays : days));
	}

	/** Lenient: any case, dashes or spaces for underscores; anything unrecognised means {@link #ANY}. */
	public static SearchWindow parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return ANY;
		}
		String normalized = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		for (SearchWindow window : values()) {
			if (window.name().equals(normalized)) {
				return window;
			}
		}
		return ANY;
	}
}
