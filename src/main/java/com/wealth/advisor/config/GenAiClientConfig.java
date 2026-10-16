package com.wealth.advisor.config;

import com.google.genai.Client;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A standalone {@code genai} {@link Client}, separate from the one ADK builds internally for
 * {@code LlmAgent}s (that one is private to ADK's {@code Gemini} class). Used for calls that sit
 * outside ADK's agent abstraction: {@link com.wealth.advisor.portfolio.HoldingsExtractionService}'s
 * structured-output extraction and the compaction summarizer.
 *
 * <p>Builder with no explicit key/project/credentials resolves auth from the same env vars as
 * the agent's model does: {@code GOOGLE_GENAI_USE_VERTEXAI}, {@code GOOGLE_CLOUD_PROJECT}, {@code
 * GOOGLE_CLOUD_LOCATION} (Vertex/ADC), or {@code GOOGLE_API_KEY}/{@code GEMINI_API_KEY} otherwise.
 */
@Configuration
public class GenAiClientConfig {

	@Bean
	public Client genAiClient() {
		return Client.builder().build();
	}
}
