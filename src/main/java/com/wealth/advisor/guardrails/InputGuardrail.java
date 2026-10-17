package com.wealth.advisor.guardrails;

import java.util.List;

/**
 * One layer of the input guardrail chain. Implementations are Spring beans ordered with {@code
 * @Order} — cheapest first — and {@link GuardrailChain} stops at the first rejection.
 */
public interface InputGuardrail {

	/**
	 * @param message the user's latest message
	 * @param recentMessages the most recent <em>accepted</em> messages of the conversation, oldest
	 *     first; empty for a new conversation
	 */
	GuardrailVerdict check(String message, List<String> recentMessages);
}
