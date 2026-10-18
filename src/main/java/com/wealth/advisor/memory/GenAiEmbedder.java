package com.wealth.advisor.memory;

import com.google.genai.Client;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentConfig;
import com.google.genai.types.EmbedContentResponse;
import com.wealth.advisor.config.AdvisorProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/** {@link Embedder} backed by the shared {@code genai} client and the configured embedding model. */
@Component
public class GenAiEmbedder implements Embedder {

	private final Client client;
	private final String model;
	private final int dimensions;

	public GenAiEmbedder(Client genAiClient, AdvisorProperties properties) {
		this.client = genAiClient;
		this.model = properties.episodes().embeddingModel();
		this.dimensions = properties.episodes().embeddingDimensions();
	}

	@Override
	public double[] embedDocument(String text) {
		return embed(text, "RETRIEVAL_DOCUMENT");
	}

	@Override
	public double[] embedQuery(String text) {
		return embed(text, "RETRIEVAL_QUERY");
	}

	private double[] embed(String text, String taskType) {
		EmbedContentConfig config = EmbedContentConfig.builder().taskType(taskType).outputDimensionality(dimensions).build();
		try {
			EmbedContentResponse response = client.models.embedContent(model, text, config);
			List<Float> values = response.embeddings()
					.flatMap(embeddings -> embeddings.stream().findFirst())
					.flatMap(ContentEmbedding::values)
					.orElseThrow(() -> new IllegalStateException("The embedding model returned no vector"));
			double[] vector = new double[values.size()];
			for (int i = 0; i < vector.length; i++) {
				vector[i] = values.get(i);
			}
			return vector;
		} catch (RuntimeException e) {
			throw new IllegalStateException("Embedding call failed", e);
		}
	}
}
