package com.wealth.advisor.guardrails;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * L1: a microsecond-cheap filter for the lazy, high-volume attempts — "ignore previous
 * instructions", "show me your system prompt", "you are now DAN". It is <strong>not</strong> the
 * injection defense: a paraphrase gets past any phrase list, and falls through to the L2 scope
 * classifier, which judges meaning rather than wording.
 *
 * <p>The text is normalized first (compatibility-folded, invisible format characters removed,
 * lower-cased, punctuation turned into spaces) so that casing, spacing, and zero-width tricks
 * don't dodge the patterns.
 */
@Component
@Order(1)
public class HeuristicInputGuardrail implements InputGuardrail {

	static final String LAYER = "L1_HEURISTIC";

	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\p{Cc}&&[^\\s]]");
	private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

	private static final List<NamedPattern> PATTERNS = List.of(
			new NamedPattern(
					"instruction override",
					"\\b(ignore|disregard|forget|override|bypass)\\b.{0,30}\\b(previous|prior|earlier|above|old|all|your|any)\\b"
							+ ".{0,30}\\b(instructions?|rules|prompts?|guidelines|directives?)\\b"),
			new NamedPattern(
					"system prompt extraction",
					"\\b(reveal|show|print|repeat|output|display|leak|tell|give|share|say)\\b.{0,30}\\b(system|initial|hidden|original)\\s+(prompt|instructions?)\\b"),
			new NamedPattern(
					"persona jailbreak",
					"\\b(you are now|act as|pretend to be)\\b.{0,40}\\b(dan|unrestricted|jailbroken|no restrictions)\\b"),
			new NamedPattern("jailbreak keyword", "\\b(jailbreak|developer mode|dan mode)\\b"));

	@Override
	public GuardrailVerdict check(String message, List<String> recentMessages) {
		String normalized = normalize(message);
		for (NamedPattern p : PATTERNS) {
			if (p.pattern().matcher(normalized).find()) {
				return GuardrailVerdict.reject(LAYER, p.name());
			}
		}
		return GuardrailVerdict.ALLOW;
	}

	static String normalize(String message) {
		String folded = Normalizer.normalize(message, Normalizer.Form.NFKC);
		String visible = INVISIBLE.matcher(folded).replaceAll("");
		return NON_WORD.matcher(visible.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
	}

	private record NamedPattern(String name, Pattern pattern) {
		NamedPattern(String name, String regex) {
			this(name, Pattern.compile(regex));
		}
	}
}
