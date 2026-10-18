package com.wealth.advisor.guardrails;

/**
 * The outcome of an input guardrail. A rejection names the {@code layer} that fired and a short
 * {@code reason}, both for the log line and — later — the audit row; neither is shown to the user.
 */
public record GuardrailVerdict(boolean allowed, String layer, String reason) {

	public static final GuardrailVerdict ALLOW = new GuardrailVerdict(true, null, null);

	public static GuardrailVerdict reject(String layer, String reason) {
		return new GuardrailVerdict(false, layer, reason);
	}
}
