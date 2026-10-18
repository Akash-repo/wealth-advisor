package com.wealth.advisor.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.wealth.advisor.agent.prompt.PromptLoader;
import com.wealth.advisor.config.AdvisorProperties;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Asks a small model which durable preferences a user stated in a conversation, as a list of
 * proposed {@link PreferenceOperation}s. A plain {@code genai} call with a response schema and no
 * tools (the pattern of {@code HoldingsExtractionService}). It sees only the user's own messages and the
 * current profile, never agent replies. What is applied is decided by {@link PreferenceReconciler}.
 */
@Component
public class PreferenceExtractor {

	private static final Logger log = LoggerFactory.getLogger(PreferenceExtractor.class);

	/** Oldest messages are left out beyond this many characters; earlier runs already read them. */
	static final int MAX_MESSAGE_CHARS = 40_000;

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Response(List<PreferenceOperation> operations) {}

	private final UnaryOperator<String> model;
	private final ObjectMapper objectMapper;
	private final String template;

	@Autowired
	public PreferenceExtractor(Client genAiClient, AdvisorProperties properties, ObjectMapper objectMapper, PromptLoader promptLoader) {
		this(
				prompt -> genAiClient
						.models
						.generateContent(properties.profile().extractorModel(), prompt, config())
						.text(),
				objectMapper,
				promptLoader);
	}

	/** Test seam: {@code model} maps the full prompt to the model's raw JSON reply. */
	PreferenceExtractor(UnaryOperator<String> model, ObjectMapper objectMapper, PromptLoader promptLoader) {
		this.model = model;
		this.objectMapper = objectMapper;
		this.template = promptLoader.load("preference_extractor").text();
	}

	/** @throws IllegalStateException if the model call fails or returns something unparseable */
	public List<PreferenceOperation> extract(PreferenceProfile profile, List<TimedMessage> userMessages) {
		if (userMessages.isEmpty()) {
			return List.of();
		}
		String json;
		try {
			json = model.apply(prompt(profile, userMessages));
		} catch (RuntimeException e) {
			throw new IllegalStateException("Preference extraction call failed", e);
		}
		List<PreferenceOperation> operations = parse(json);
		log.debug("Preference extractor proposed {} operation(s) from {} message(s)", operations.size(), userMessages.size());
		return operations;
	}

	String prompt(PreferenceProfile profile, List<TimedMessage> userMessages) {
		return PromptTemplate.fill(
				template,
				Map.of(
						"sectors", String.join("; ", Sector.labels()),
						"industry_groups", String.join("; ", IndustryGroup.labels()),
						"profile", profile.forExtractor(),
						"messages", numbered(userMessages)));
	}

	private List<PreferenceOperation> parse(String json) {
		try {
			Response response = objectMapper.readValue(json, Response.class);
			return response.operations() == null ? List.of() : response.operations();
		} catch (JsonProcessingException | RuntimeException e) {
			throw new IllegalStateException("Preference extraction returned unparseable output", e);
		}
	}

	private static String numbered(List<TimedMessage> messages) {
		StringBuilder text = new StringBuilder();
		for (int i = messages.size() - 1; i >= 0; i--) {
			TimedMessage m = messages.get(i);
			String line = "[" + m.index() + "] (" + m.at() + ") " + m.text() + "\n";
			if (text.length() + line.length() > MAX_MESSAGE_CHARS && text.length() > 0) {
				break;
			}
			text.insert(0, line);
		}
		return text.toString();
	}

	private static GenerateContentConfig config() {
		return GenerateContentConfig.builder()
				.temperature(0.0f)
				.responseMimeType("application/json")
				.responseSchema(responseSchema())
				.build();
	}

	private static Schema responseSchema() {
		Schema operation = Schema.builder()
				.type(Type.Known.OBJECT)
				.properties(Map.of(
						"op", enumField(Arrays.stream(PreferenceOperation.Op.values()).map(Enum::name).toList()),
						"category", enumField(Arrays.stream(PreferenceCategory.values()).map(Enum::name).toList()),
						"value", field(Type.Known.STRING),
						"replaces", field(Type.Known.STRING),
						"evidenceQuote", field(Type.Known.STRING),
						"messageIndex", field(Type.Known.INTEGER),
						"confidence", field(Type.Known.NUMBER),
						"note", field(Type.Known.STRING)))
				.required("op", "category", "value", "evidenceQuote", "messageIndex", "confidence")
				.build();
		return Schema.builder()
				.type(Type.Known.OBJECT)
				.properties(Map.of("operations", Schema.builder().type(Type.Known.ARRAY).items(operation).build()))
				.required("operations")
				.build();
	}

	private static Schema enumField(List<String> values) {
		return Schema.builder().type(Type.Known.STRING).enum_(values).build();
	}

	private static Schema field(Type.Known type) {
		return Schema.builder().type(type).build();
	}
}
