package com.wealth.advisor.portfolio;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wraps Gemini's embedding model (768-dimensional vectors, per the design doc's FR-2) for both
 * writing portfolio chunks and reading a query back out at search time — both sides of a vector
 * search have to use the same embedding model for the distances to mean anything.
 *
 * <p>An "embedding" here is just a fixed-length array of numbers representing the meaning of a
 * piece of text — texts with similar meaning end up as vectors that are close together in that
 * 768-dimensional space. That's the entire basis for vector search: embed the user's question,
 * embed every stored chunk, and find the chunks whose vectors are nearest to the question's.
 *
 * <pre>{@code
 * double[] vector = embeddingService.embed("What is my technology sector exposure?");
 * // vector.length == 768; store it or compare it against stored vectors via Firestore's
 * // findNearest(...) — see PortfolioIngestionService (writes) and PortfolioSearchTool (reads).
 * }</pre>
 *
 * <p>Uses a plain {@link Client} (this project's own {@code GenAiClientConfig} bean), not ADK's
 * {@code LlmAgent}/{@code Gemini} classes — embeddings aren't part of ADK's agent abstraction,
 * they're a separate `genai` SDK call.
 */
@Component
public class EmbeddingService {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
	private static final String MODEL = "text-embedding-004";

	private final Client genAiClient;

	public EmbeddingService(Client genAiClient) {
		this.genAiClient = genAiClient;
	}

	/**
	 * @param text the text to embed — a portfolio chunk when writing, or a user's question when
	 *     searching
	 * @return a 768-dimensional embedding vector for {@code text}
	 */
	public double[] embed(String text) {
		EmbedContentResponse response =
				genAiClient.models.embedContent(MODEL, text, EmbedContentConfig.builder().build());
		List<Float> values =
				response
						.embeddings()
						.flatMap(embeddings -> embeddings.stream().findFirst())
						.flatMap(ContentEmbedding::values)
						.orElseThrow(() -> new IllegalStateException("No embedding returned for: " + text));

		double[] vector = new double[values.size()];
		for (int i = 0; i < values.size(); i++) {
			vector[i] = values.get(i);
		}
		log.debug("Embedded {} chars of text into a {}-dimensional vector", text.length(), vector.length);
		return vector;
	}
}
