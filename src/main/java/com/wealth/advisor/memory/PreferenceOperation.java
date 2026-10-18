package com.wealth.advisor.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One change to the profile proposed by the extractor model. It is only a proposal:
 * {@link PreferenceReconciler} decides whether to apply it. {@code evidenceQuote} must be the user's
 * exact words from message number {@code messageIndex}; {@code replaces} names the existing value an
 * {@link Op#UPDATE} supersedes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreferenceOperation(
		Op op,
		PreferenceCategory category,
		String value,
		String replaces,
		String evidenceQuote,
		int messageIndex,
		double confidence,
		String note) {

	public enum Op {
		ADD,
		UPDATE,
		REMOVE
	}
}
