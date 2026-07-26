package com.wealth.advisor.portfolio;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Wraps Gemini's embedding model (768-dimensional vectors, per the design doc's FR-2) for both
 * writing portfolio chunks and reading a query back out at search time — both sides of a vector
 * search have to use the same embedding model for the distances to mean anything.
 */
@Component
public class EmbeddingService {

	private static final String MODEL = "text-embedding-004";

	private final Client genAiClient;

	public EmbeddingService(Client genAiClient) {
		this.genAiClient = genAiClient;
	}

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
		return vector;
	}
}
