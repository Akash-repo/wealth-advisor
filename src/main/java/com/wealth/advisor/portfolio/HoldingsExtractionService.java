package com.wealth.advisor.portfolio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Extracts individual stock/ETF holdings out of a brokerage statement's text, as structured data
 * — not prose chunks — so exact aggregation ("total GOOG shares across every account") can be a
 * real Firestore query summed in Java, rather than hoping RAG's top-K vector search happens to
 * retrieve every chunk that mentions a given ticker. See {@code ARCHITECTURE.md}'s "Portfolio
 * holdings" section for why chunk-based RAG alone isn't reliable for aggregation questions.
 *
 * <p>Uses the same plain {@code genai} {@link Client} as {@link EmbeddingService} (not ADK's
 * agent framework — this runs once at ingestion time, not as something the chat agent calls), but
 * a structured-output {@code generateContent} call instead of an embedding call: {@code
 * responseMimeType("application/json")} + a {@code responseSchema} constrains Gemini to return a
 * JSON array matching exactly the shape this app needs, rather than free-form text that would
 * need fragile regex/prose parsing.
 */
@Component
public class HoldingsExtractionService {

	private static final Logger log = LoggerFactory.getLogger(HoldingsExtractionService.class);
	private static final String MODEL = "gemini-2.5-flash";

	private final Client genAiClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public HoldingsExtractionService(Client genAiClient) {
		this.genAiClient = genAiClient;
	}

	/**
	 * @param documentText the full extracted text of an uploaded document
	 * @return one map per holding found ({@code ticker}, {@code shares}, {@code costBasis}
	 *     (nullable), {@code account} (nullable)) — an empty list for documents with no holdings
	 *     (a resume, a bank statement) rather than an error, since not every uploaded document is
	 *     expected to contain any
	 */
	public List<Map<String, Object>> extract(String documentText) {
		try {
			GenerateContentConfig config =
					GenerateContentConfig.builder()
							.responseMimeType("application/json")
							.responseSchema(responseSchema())
							.build();

			String prompt =
					"""
					Extract every individual stock or ETF holding mentioned in the following document.
					For each holding found, report its ticker symbol, the number of shares held, its cost
					basis per share if the document states one, and the brokerage/account name if the
					document states one. If this document contains no stock/ETF holdings at all (e.g. it's
					a resume or a bank statement), return an empty array — do not invent holdings.

					Document text:
					"""
							+ documentText;

			GenerateContentResponse response = genAiClient.models.generateContent(MODEL, prompt, config);
			List<Map<String, Object>> holdings =
					objectMapper.readValue(response.text(), new TypeReference<>() {});
			log.debug("Extracted {} holding(s) from document text", holdings.size());
			return holdings;
		} catch (Exception e) {
			// Extraction is a best-effort enrichment on top of the RAG chunks, which are already
			// stored regardless — a failure here shouldn't fail the whole upload.
			log.warn("Holdings extraction failed, continuing without structured holdings: {}", e.getMessage());
			return List.of();
		}
	}

	private static Schema responseSchema() {
		return Schema.builder()
				.type(Type.Known.ARRAY)
				.items(
						Schema.builder()
								.type(Type.Known.OBJECT)
								.properties(
										Map.of(
												"ticker",
												Schema.builder().type(Type.Known.STRING).description("Stock ticker symbol").build(),
												"shares",
												Schema.builder().type(Type.Known.NUMBER).description("Number of shares held").build(),
												"costBasis",
												Schema.builder()
														.type(Type.Known.NUMBER)
														.description("Cost basis per share, if stated in the document")
														.build(),
												"account",
												Schema.builder()
														.type(Type.Known.STRING)
														.description("Brokerage or account name, if stated in the document")
														.build()))
								.required("ticker", "shares")
								.build())
				.build();
	}
}
