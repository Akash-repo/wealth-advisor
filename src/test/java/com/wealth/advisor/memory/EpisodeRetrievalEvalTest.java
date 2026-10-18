package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.genai.Client;
import com.wealth.advisor.config.TestProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Retrieval quality with the real embedding model and the real ranker, on a small set of realistic
 * episodes: a paraphrase finds the right one, an exact ticker lifts the right one, recency breaks a tie,
 * and an unrelated question finds nothing. Also the place to see whether the configured minimum score and
 * weights suit the real model's scores. Skips itself without Gemini credentials. Tagged {@code eval}.
 */
@Tag("eval")
class EpisodeRetrievalEvalTest {

	private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

	private static Embedder embedder;
	private static final EpisodeRanker RANKER = new EpisodeRanker(TestProperties.defaults());
	private static List<Episode> episodes;

	@BeforeAll
	static void connect() {
		assumeTrue(
				"true".equalsIgnoreCase(System.getenv("GOOGLE_GENAI_USE_VERTEXAI"))
						|| System.getenv("GOOGLE_API_KEY") != null
						|| System.getenv("GEMINI_API_KEY") != null,
				"no Gemini credentials configured");
		embedder = new GenAiEmbedder(Client.builder().build(), TestProperties.defaults());
		episodes = new ArrayList<>();
		episodes.add(episode("jpm", "JPMorgan dividend idea",
				"The user asked whether to add to their JPMorgan position for its dividend. The assistant noted the 2.3 percent yield but, since the user already holds a lot of financials, suggested holding off.",
				List.of("JPM"), 6));
		episodes.add(episode("retire", "Retirement savings target",
				"The user asked how much to have saved for retirement by age 40. The assistant gave the rule of thumb of three times annual salary and suggested a stock-heavy allocation for a 25-year horizon.",
				List.of(), 9));
		episodes.add(episode("rsu", "RSU vesting and tax",
				"The user asked how their employer stock grants are taxed when they vest next month. The assistant explained ordinary income tax at vesting and suggested selling to avoid concentration.",
				List.of(), 12));
		episodes.add(episode("nvda", "NVIDIA valuation",
				"The user compared NVIDIA with other technology stocks on forward price to earnings and PEG. The assistant said the valuation is rich but supported by fast earnings growth.",
				List.of("NVDA"), 20));
		episodes.add(episode("energy", "Avoiding energy stocks",
				"The user said they do not want oil and gas companies in their portfolio for ethical reasons, and the assistant agreed to leave the energy sector out of recommendations.",
				List.of("XOM"), 40));
	}

	private static Episode episode(String id, String title, String summary, List<String> tickers, int daysAgo) {
		Instant end = NOW.minus(Duration.ofDays(daysAgo));
		Episode e = new Episode(id, "u", "s", List.of(new MessageRange(1, 4)), end.minusSeconds(900), end, title, summary, tickers, List.of(), List.of(), List.of(), null);
		return e.withEmbedding(embedder.embedDocument(e.embeddingText()));
	}

	private static List<EpisodeRanker.Scored> search(String topic, String... tickers) {
		return RANKER.rank(episodes, embedder.embedQuery(topic), List.of(tickers), NOW);
	}

	private static String describe(List<EpisodeRanker.Scored> hits) {
		return hits.stream().map(h -> h.episode().id() + "=" + Math.round(h.score() * 100) / 100.0).toList().toString();
	}

	@Test
	void aParaphraseFindsTheRightEpisodeFirst() {
		assertThat(search("that bank stock idea we talked about").get(0).episode().id()).isEqualTo("jpm");
		assertThat(search("what you said about my company stock grants").get(0).episode().id()).isEqualTo("rsu");
		assertThat(search("how expensive is the chip maker").get(0).episode().id()).isEqualTo("nvda");
		assertThat(search("how much should I have put away for when I stop working").get(0).episode().id()).isEqualTo("retire");
	}

	@Test
	void aNamedTickerLiftsItsEpisode() {
		List<EpisodeRanker.Scored> hits = search("valuation", "NVDA");

		assertThat(hits.get(0).episode().id()).as(describe(hits)).isEqualTo("nvda");
	}

	@Test
	void anUnrelatedQuestionFindsNothing() {
		List<EpisodeRanker.Scored> hits = search("a good recipe for pasta carbonara");

		assertThat(hits).as(describe(hits)).isEmpty();
	}
}
