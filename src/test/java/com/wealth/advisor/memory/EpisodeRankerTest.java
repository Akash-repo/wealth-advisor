package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.wealth.advisor.config.TestProperties;
import com.wealth.advisor.memory.EpisodeRanker.Scored;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EpisodeRankerTest {

	private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

	private final EpisodeRanker ranker = new EpisodeRanker(TestProperties.defaults());

	private static Episode episode(String id, String text, List<String> tickers, Instant end) {
		return new Episode(id, "u", "s", List.of(new MessageRange(1, 2)), end.minusSeconds(600), end, text, text, tickers, List.of(), List.of(), List.of(), FakeEmbedder.vector(text));
	}

	@Test
	void cosineIsOneForIdenticalVectorsZeroForUnrelatedAndZeroForMismatchedLengths() {
		assertThat(EpisodeRanker.cosine(new double[] {1, 2, 3}, new double[] {1, 2, 3})).isCloseTo(1.0, within(1e-9));
		assertThat(EpisodeRanker.cosine(new double[] {1, 0}, new double[] {0, 1})).isZero();
		assertThat(EpisodeRanker.cosine(new double[] {1, 0}, new double[] {1, 0, 0})).isZero();
		assertThat(EpisodeRanker.cosine(new double[] {0, 0}, new double[] {1, 0})).isZero();
	}

	@Test
	void theMoreSimilarEpisodeRanksFirst() {
		Instant sameTime = NOW.minusSeconds(86_400);
		Episode bank = episode("bank", "jpmorgan bank dividend", List.of(), sameTime);
		Episode retirement = episode("ret", "retirement retire", List.of(), sameTime);

		List<Scored> ranked = ranker.rank(List.of(retirement, bank), FakeEmbedder.vector("that bank dividend idea"), List.of(), NOW);

		assertThat(ranked).extracting(s -> s.episode().id()).containsExactly("bank");
	}

	@Test
	void anExactTickerMatchLiftsAnEpisodeThatMeaningAloneWouldScoreLow() {
		Instant sameTime = NOW.minusSeconds(86_400);
		Episode tagged = episode("tagged", "retirement retire", List.of("JPM"), sameTime);
		Episode untagged = episode("untagged", "retirement retire", List.of(), sameTime);

		List<Scored> ranked = ranker.rank(List.of(untagged, tagged), FakeEmbedder.vector("retirement"), List.of("jpm"), NOW);

		assertThat(ranked.get(0).episode().id()).isEqualTo("tagged");
		assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
	}

	@Test
	void aNewerEpisodeBeatsAnOlderOneWhenOtherwiseEqual() {
		Episode recent = episode("recent", "jpmorgan bank", List.of(), NOW.minusSeconds(86_400));
		Episode old = episode("old", "jpmorgan bank", List.of(), NOW.minusSeconds(86_400L * 120));

		List<Scored> ranked = ranker.rank(List.of(old, recent), FakeEmbedder.vector("jpmorgan bank"), List.of(), NOW);

		assertThat(ranked).extracting(s -> s.episode().id()).containsExactly("recent", "old");
	}

	@Test
	void recencyHalvesEveryHalfLife() {
		Episode atHalfLife = episode("a", "jpmorgan", List.of(), NOW.minusSeconds(30L * 86_400));
		Episode fresh = episode("b", "jpmorgan", List.of(), NOW);
		double[] query = FakeEmbedder.vector("jpmorgan");

		double staleScore = ranker.rank(List.of(atHalfLife), query, List.of(), NOW).get(0).score();
		double freshScore = ranker.rank(List.of(fresh), query, List.of(), NOW).get(0).score();

		// score = (0.6 × 1 + 0.1 × recency) / 0.7: recency 1.0 versus 0.5
		assertThat(freshScore).isCloseTo((0.6 + 0.1) / 0.7, within(1e-6));
		assertThat(staleScore).isCloseTo((0.6 + 0.05) / 0.7, within(1e-6));
	}

	@Test
	void withoutTickersTheScoreStaysOnAZeroToOneScale() {
		Episode perfect = episode("p", "jpmorgan", List.of(), NOW);

		assertThat(ranker.rank(List.of(perfect), FakeEmbedder.vector("jpmorgan"), List.of(), NOW).get(0).score()).isCloseTo(1.0, within(1e-6));
	}

	@Test
	void similarityIsRescaledBetweenTheFloorAndTheCeiling() {
		double[] query = {1, 0};
		Episode atHalfway = withVector("a", new double[] {0.625, Math.sqrt(1 - 0.625 * 0.625)});
		Episode belowFloor = withVector("b", new double[] {0.4, Math.sqrt(1 - 0.16)});

		List<Scored> ranked = ranker.rank(List.of(atHalfway, belowFloor), query, List.of(), NOW);

		assertThat(ranked).hasSize(1);
		assertThat(ranked.get(0).similarity()).isCloseTo(0.5, within(1e-9));
	}

	@Test
	void beingRecentCannotRescueAnEpisodeThatIsNotRelevant() {
		Episode freshButUnrelated = episode("fresh", "retirement retire", List.of(), NOW);

		assertThat(ranker.rank(List.of(freshButUnrelated), FakeEmbedder.vector("energy"), List.of(), NOW)).isEmpty();
	}

	@Test
	void aTickerMatchAloneMakesAnEpisodeRelevantEnoughToReturn() {
		Episode tagged = episode("tagged", "retirement retire", List.of("JPM"), NOW.minusSeconds(86_400));

		assertThat(ranker.rank(List.of(tagged), FakeEmbedder.vector("energy"), List.of("JPM"), NOW)).hasSize(1);
	}

	private static Episode withVector(String id, double[] vector) {
		return episode(id, "text", List.of(), NOW).withEmbedding(vector);
	}

	@Test
	void episodesBelowTheMinimumScoreAreDropped() {
		Episode unrelated = episode("x", "retirement", List.of(), NOW.minusSeconds(86_400L * 400));

		assertThat(ranker.rank(List.of(unrelated), FakeEmbedder.vector("energy"), List.of(), NOW)).isEmpty();
	}

	@Test
	void atMostTheConfiguredNumberOfResultsAreReturned() {
		List<Episode> many = new java.util.ArrayList<>();
		for (int i = 0; i < 9; i++) {
			many.add(episode("e" + i, "jpmorgan bank", List.of(), NOW.minusSeconds(3600L * i)));
		}

		assertThat(ranker.rank(many, FakeEmbedder.vector("jpmorgan bank"), List.of(), NOW)).hasSize(5);
	}
}
