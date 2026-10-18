package com.wealth.advisor.memory;

import java.util.Optional;

/** An enum constant with a human-readable name, which is also what the extractor model is shown and returns. */
public interface Labeled {

	String label();

	/** The constant whose label equals {@code label}, ignoring case and surrounding space. */
	static <E extends Enum<E> & Labeled> Optional<E> find(E[] values, String label) {
		if (label == null) {
			return Optional.empty();
		}
		String wanted = label.strip();
		for (E value : values) {
			if (value.label().equalsIgnoreCase(wanted)) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}
}
