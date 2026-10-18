package com.wealth.advisor.memory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code {name}} placeholders in a prompt file. Done in one pass over the template, so text
 * that is substituted in (a user's message, a stored preference) is never scanned for placeholders
 * itself: a message containing the literal text {@code {messages}} stays as written.
 */
final class PromptTemplate {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

	private PromptTemplate() {}

	/** Unknown placeholders are left as they are. */
	static String fill(String template, Map<String, String> values) {
		Matcher matcher = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String value = values.get(matcher.group(1));
			matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : matcher.group()));
		}
		matcher.appendTail(out);
		return out.toString();
	}
}
