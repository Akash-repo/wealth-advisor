package com.wealth.advisor.memory;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What counts as a valid value for each {@link PreferenceCategory}, in one place so a preference typed
 * in the UI and one proposed by the extractor are held to the same rules: a sector or industry group
 * must be on the fixed list, a ticker must look like one, and free text is collapsed to a single short
 * line.
 */
public final class PreferenceValues {

	private static final Pattern TICKER = Pattern.compile("[A-Z][A-Z0-9.\\-]{0,9}");
	private static final Pattern WHITESPACE_OR_CONTROL = Pattern.compile("[\\s\\p{Cntrl}]+");
	public static final int MAX_LENGTH = 200;

	private PreferenceValues() {}

	/** The value in its stored form, or empty if it is not valid for the category. */
	public static Optional<String> normalize(PreferenceCategory category, String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		return switch (category) {
			case EXCLUDED_SECTORS -> Sector.fromLabel(raw).map(Sector::label);
			case EXCLUDED_INDUSTRY_GROUPS -> IndustryGroup.fromLabel(raw).map(IndustryGroup::label);
			case EXCLUDED_TICKERS -> Optional.of(raw.strip().toUpperCase(Locale.ROOT)).filter(t -> TICKER.matcher(t).matches());
			default -> Optional.of(singleLine(raw)).filter(t -> !t.isEmpty() && t.length() <= MAX_LENGTH);
		};
	}

	/** Whitespace and control characters (including line breaks) collapsed to single spaces. */
	public static String singleLine(String text) {
		return WHITESPACE_OR_CONTROL.matcher(text).replaceAll(" ").strip();
	}
}
