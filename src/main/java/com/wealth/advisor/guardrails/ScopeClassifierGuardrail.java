package com.wealth.advisor.guardrails;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.wealth.advisor.agent.prompt.PromptLoader;
import com.wealth.advisor.config.AdvisorProperties;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * L2: a small, cheap model judges whether the latest message is within the advisor's scope. It is
 * a gate in front of the agents — a plain {@code genai} call with no tools and no session — not a
 * stage in the pipeline. It judges meaning, so it catches the paraphrases L1's phrase list cannot.
 *
 * <p>If the classifier call itself fails, {@code advisor.guardrails.fail-closed} decides: {@code
 * true} (the configured setting) refuses the message, {@code false} lets it through and logs it.
 */
@Component
@Order(2)
public class ScopeClassifierGuardrail implements InputGuardrail {

	static final String LAYER = "L2_SCOPE_CLASSIFIER";
	private static final String IN_SCOPE = "IN_SCOPE";

	private static final Logger log = LoggerFactory.getLogger(ScopeClassifierGuardrail.class);

	private final UnaryOperator<String> model;
	private final ObjectMapper objectMapper;
	private final String instruction;
	private final boolean failClosed;

	@Autowired
	public ScopeClassifierGuardrail(
			Client genAiClient, AdvisorProperties properties, ObjectMapper objectMapper, PromptLoader promptLoader) {
		this(
				prompt -> genAiClient
						.models
						.generateContent(properties.guardrails().classifierModel(), prompt, config())
						.text(),
				properties,
				objectMapper,
				promptLoader);
	}

	/** Test seam: {@code model} maps the full classifier prompt to the model's raw JSON reply. */
	ScopeClassifierGuardrail(
			UnaryOperator<String> model,
			AdvisorProperties properties,
			ObjectMapper objectMapper,
			PromptLoader promptLoader) {
		this.model = model;
		this.objectMapper = objectMapper;
		this.instruction = promptLoader.load("scope_classifier").text();
		this.failClosed = properties.guardrails().failClosed();
	}

	@Override
	public GuardrailVerdict check(String message, List<String> recentMessages) {
		String verdict;
		try {
			verdict = parse(model.apply(prompt(message, recentMessages)));
		} catch (RuntimeException e) {
			return onClassifierFailure(e);
		}
		return IN_SCOPE.equals(verdict) ? GuardrailVerdict.ALLOW : GuardrailVerdict.reject(LAYER, verdict);
	}

	private GuardrailVerdict onClassifierFailure(RuntimeException e) {
		if (failClosed) {
			log.warn("Scope classifier failed; failing closed", e);
			return GuardrailVerdict.reject(LAYER, "CLASSIFIER_ERROR");
		}
		log.warn("Scope classifier failed; failing open", e);
		return GuardrailVerdict.ALLOW;
	}

	private String prompt(String message, List<String> recentMessages) {
		StringBuilder prompt = new StringBuilder(instruction).append("Recent messages (oldest first):\n");
		if (recentMessages.isEmpty()) {
			prompt.append("(none)\n");
		}
		recentMessages.forEach(m -> prompt.append("- ").append(m).append('\n'));
		return prompt.append("\nLatest message:\n<<<\n").append(message).append("\n>>>").toString();
	}

	private String parse(String json) {
		try {
			String verdict = objectMapper.readTree(json).path("verdict").asText();
			if (verdict.isBlank()) {
				throw new IllegalStateException("Scope classifier returned no verdict");
			}
			return verdict;
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Scope classifier returned unparseable output", e);
		}
	}

	private static GenerateContentConfig config() {
		return GenerateContentConfig.builder()
				.temperature(0.0f)
				.responseMimeType("application/json")
				.responseSchema(Schema.builder()
						.type(Type.Known.OBJECT)
						.properties(Map.of(
								"verdict",
								Schema.builder()
										.type(Type.Known.STRING)
										.enum_(List.of(IN_SCOPE, "OFF_TOPIC", "UNSAFE"))
										.build()))
						.required("verdict")
						.build())
				.build();
	}
}
