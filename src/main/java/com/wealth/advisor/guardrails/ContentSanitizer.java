package com.wealth.advisor.guardrails;

import org.springframework.stereotype.Component;

/**
 * Where "is this content safe to use here" logic lives for text that originates outside this
 * app's own code — a tool's ticker argument, an external API's response, and (eventually) raw
 * user prompts or LLM/tool output. Kept separate from the classes that actually call
 * tools/APIs (e.g. {@code MarketDataService}) so that concern doesn't get scattered as inline
 * checks across every call site.
 *
 * <p><strong>Today: log forging (CWE-117) only.</strong> Untrusted text containing a carriage
 * return or line feed can inject a fake log line, or split a real one into something misleading,
 * once it reaches a text log via SLF4J — parameterized logging ({@code log.warn("...{}", value)})
 * does <em>not</em> protect against this by itself, since the substituted value's own control
 * characters still reach the log output verbatim. {@link #sanitizeForLog} strips them.
 *
 * <p>User-prompt scope checks live next to this class as {@link InputGuardrail} layers, run by
 * {@link GuardrailChain}.
 *
 * <p>Deliberately narrow for now. Validating/sanitizing full LLM responses, tool responses, or
 * user prompts against prompt injection is a real, harder problem (see this app's chat history —
 * an MCP server, or even a legitimate API, returning content crafted to manipulate the agent) and
 * isn't implemented yet. This class is where that logic should land when it is, rather than being
 * scattered across {@code MarketDataService}, {@code AgentChatService}, etc. — not built
 * speculatively ahead of an actual need.
 */
@Component
public class ContentSanitizer {

	/**
	 * Strips carriage returns, line feeds, and other ASCII control characters from {@code value}
	 * so it can't forge or split a log line when interpolated into one. {@code null} in, {@code
	 * null} out — nothing to sanitize.
	 */
	public String sanitizeForLog(String value) {
		if (value == null) {
			return null;
		}
		StringBuilder sanitized = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			sanitized.append(c < 0x20 || c == 0x7F ? '_' : c);
		}
		return sanitized.toString();
	}
}
