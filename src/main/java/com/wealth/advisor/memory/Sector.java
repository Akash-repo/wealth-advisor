package com.wealth.advisor.memory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The 11 GICS sectors: the coarse level of the controlled vocabulary an exclusion is expressed in. A
 * fixed list (not free text) so that "avoid finance" and "no financial stocks" end up as the same
 * stored value, and code can compare them exactly. See docs/design/03-user-preferences-memory.md.
 */
public enum Sector implements Labeled {
	COMMUNICATION_SERVICES("Communication Services"),
	CONSUMER_DISCRETIONARY("Consumer Discretionary"),
	CONSUMER_STAPLES("Consumer Staples"),
	ENERGY("Energy"),
	FINANCIALS("Financials"),
	HEALTH_CARE("Health Care"),
	INDUSTRIALS("Industrials"),
	INFORMATION_TECHNOLOGY("Information Technology"),
	MATERIALS("Materials"),
	REAL_ESTATE("Real Estate"),
	UTILITIES("Utilities");

	private final String label;

	Sector(String label) {
		this.label = label;
	}

	@Override
	public String label() {
		return label;
	}

	public static Optional<Sector> fromLabel(String label) {
		return Labeled.find(values(), label);
	}

	public static List<String> labels() {
		return Arrays.stream(values()).map(Sector::label).toList();
	}
}
