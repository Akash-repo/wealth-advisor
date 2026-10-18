package com.wealth.advisor.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.wealth.advisor.agent.prompt.PromptLoader;
import com.wealth.advisor.config.AdvisorProperties;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads the individual stock/ETF holdings out of a statement's text as structured data.
 *
 * <p>This is a plain structured-output Gemini call, run once at upload time (not something the chat
 * agent calls): {@code responseMimeType("application/json")} plus a {@code responseSchema}
 * constrains the model to a JSON array of exactly the shape {@link Holding} needs, so there is no
 * fragile prose parsing.
 *
 * <p>This step decides whether an upload is accepted at all, so it must not hide failures: a model
 * or parsing error throws {@link IllegalStateException}. Returning an empty list is reserved for
 * "the model read the document and found no holdings".
 */
@Component
public class HoldingsExtractionService {

	private static final Logger log = LoggerFactory.getLogger(HoldingsExtractionService.class);

	private final Client genAiClient;
	private final String model;
	private final ObjectMapper objectMapper;
	private final String instruction;

	public HoldingsExtractionService(
			Client genAiClient, AdvisorProperties properties, ObjectMapper objectMapper, PromptLoader promptLoader) {
		this.genAiClient = genAiClient;
		this.model = properties.model().extraction();
		this.objectMapper = objectMapper;
		this.instruction = promptLoader.load("holdings_extraction").text();
	}

	/**
	 * @return the usable holdings found; empty if the document contains none
	 * @throws IllegalStateException if the model call fails or returns something unparseable
	 */
	public List<Holding> extract(String documentText) {
		return parse(generate(documentText));
	}

	private String generate(String documentText) {
		try {
			return genAiClient.models.generateContent(model, instruction + documentText, config()).text();
		} catch (RuntimeException e) {
			throw new IllegalStateException("Holdings extraction call failed", e);
		}
	}

	List<Holding> parse(String json) {
		try {
			List<Holding> all = objectMapper.readValue(json, new TypeReference<>() {});
			List<Holding> usable = all.stream().filter(Holding::isUsable).toList();
			log.debug("Extracted {} usable holding(s) ({} returned)", usable.size(), all.size());
			return usable;
		} catch (JsonProcessingException | IllegalArgumentException e) {
			throw new IllegalStateException("Holdings extraction returned unparseable output", e);
		}
	}

	private static GenerateContentConfig config() {
		return GenerateContentConfig.builder()
				.responseMimeType("application/json")
				.responseSchema(responseSchema())
				.build();
	}

	private static Schema responseSchema() {
		return Schema.builder()
				.type(Type.Known.ARRAY)
				.items(
						Schema.builder()
								.type(Type.Known.OBJECT)
								.properties(
										Map.of(
												"ticker", field(Type.Known.STRING, "Stock ticker symbol"),
												"shares", field(Type.Known.NUMBER, "Number of shares held"),
												"costBasis", field(Type.Known.NUMBER, "Cost basis per share, if stated in the document"),
												"account", field(Type.Known.STRING, "Brokerage or account name, if stated in the document")))
								.required("ticker", "shares")
								.build())
				.build();
	}

	private static Schema field(Type.Known type, String description) {
		return Schema.builder().type(type).description(description).build();
	}
}
