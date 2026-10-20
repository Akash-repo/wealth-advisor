package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.config.TestProperties;
import com.wealth.advisor.memory.EpisodeDraft.Range;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EpisodeValidatorTest {

	private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

	private final EpisodeValidator validator = new EpisodeValidator(TestProperties.defaults());

	/** Messages 1..n, alternating user and agent, one minute apart. */
	private static List<TimedMessage> conversation(int n) {
		List<TimedMessage> messages = new ArrayList<>();
		for (int i = 1; i <= n; i++) {
			messages.add(new TimedMessage(i, i % 2 == 1 ? TimedMessage.USER : TimedMessage.AGENT, T0.plusSeconds(60L * i), "message " + i));
		}
		return messages;
	}

	private static EpisodeDraft draft(String title, Range... ranges) {
		return new EpisodeDraft(title, "A summary of " + title + ".", List.of(ranges), List.of(), List.of(), List.of(), List.of());
	}

	@Test
	void aTopicWithTwoRangesIsOneEpisodeWithBothRanges() {
		List<Episode> episodes = validator.validate(
				List.of(draft("Valuation", new Range(1, 4), new Range(9, 10)), draft("Retirement", new Range(5, 8))),
				conversation(10), "u", "s1");

		assertThat(episodes).hasSize(2);
		assertThat(episodes.get(0).title()).isEqualTo("Valuation");
		assertThat(episodes.get(0).ranges()).containsExactly(new MessageRange(1, 4), new MessageRange(9, 10));
		assertThat(episodes.get(1).ranges()).containsExactly(new MessageRange(5, 8));
	}

	@Test
	void idsArePredictableAndFollowTheOrderOfTheConversation() {
		List<Episode> episodes = validator.validate(
				List.of(draft("Later", new Range(6, 8)), draft("Earlier", new Range(1, 5))), conversation(8), "u", "s1");

		assertThat(episodes).extracting(Episode::id).containsExactly("s1__0", "s1__1");
		assertThat(episodes).extracting(Episode::title).containsExactly("Earlier", "Later");
		assertThat(episodes).allMatch(e -> e.userId().equals("u") && e.sessionId().equals("s1"));
	}

	@Test
	void overlapsAreRemoved_theEarlierEpisodeKeepsTheMessage() {
		List<Episode> episodes = validator.validate(
				List.of(draft("First", new Range(1, 6)), draft("Second", new Range(4, 9))), conversation(10), "u", "s1");

		assertThat(episodes.get(0).ranges()).containsExactly(new MessageRange(1, 6));
		assertThat(episodes.get(1).ranges()).containsExactly(new MessageRange(7, 9));
	}

	@Test
	void anOverlapInTheMiddleSplitsTheRange() {
		List<Episode> episodes = validator.validate(
				List.of(draft("Middle", new Range(4, 6)), draft("Whole", new Range(1, 9))), conversation(10), "u", "s1");

		assertThat(episodes).extracting(Episode::title).containsExactly("Whole", "Middle");
		assertThat(episodes.get(0).ranges()).containsExactly(new MessageRange(1, 3), new MessageRange(7, 9));
	}

	@Test
	void rangesAreClampedToTheMessagesThatExist() {
		List<Episode> episodes = validator.validate(List.of(draft("Outside", new Range(-3, 50))), conversation(6), "u", "s1");

		assertThat(episodes.get(0).ranges()).containsExactly(new MessageRange(1, 6));
	}

	@Test
	void anEpisodeWithNoUsableRangeOrNoTextIsDropped() {
		EpisodeDraft noSummary = new EpisodeDraft("Title", "  ", List.of(new Range(1, 2)), null, null, null, null);
		EpisodeDraft reversed = draft("Reversed", new Range(5, 2));
		EpisodeDraft good = draft("Good", new Range(3, 4));

		List<Episode> episodes = validator.validate(List.of(noSummary, reversed, good), conversation(6), "u", "s1");

		assertThat(episodes).extracting(Episode::title).containsExactly("Good");
	}

	@Test
	void atMostTheConfiguredNumberOfEpisodesAreKept() {
		AdvisorProperties d = TestProperties.defaults();
		AdvisorProperties.Episodes e = d.episodes();
		AdvisorProperties two = d.withEpisodes(
				new AdvisorProperties.Episodes(
						e.extractorModel(), e.embeddingModel(), e.embeddingDimensions(), 2, e.maxSearchResults(), e.minScore(),
						e.recencyHalfLifeDays(), e.defaultWindowDays(), e.detailsMaxChars(), e.similarityWeight(), e.entityWeight(), e.recencyWeight(),
						e.similarityFloor(), e.similarityCeiling()));

		List<Episode> episodes = new EpisodeValidator(two).validate(
				List.of(draft("A", new Range(1, 2)), draft("B", new Range(3, 4)), draft("C", new Range(5, 6))), conversation(6), "u", "s1");

		assertThat(episodes).extracting(Episode::title).containsExactly("A", "B");
	}

	@Test
	void startAndEndComeFromTheFirstAndLastMessageOfTheRanges() {
		List<Episode> episodes = validator.validate(List.of(draft("T", new Range(2, 3), new Range(8, 9))), conversation(10), "u", "s1");

		assertThat(episodes.get(0).startTs()).isEqualTo(T0.plusSeconds(120));
		assertThat(episodes.get(0).endTs()).isEqualTo(T0.plusSeconds(540));
	}

	@Test
	void tickersAndSectorsMustBeValid_andTextIsOneLineAndCapped() {
		EpisodeDraft messy = new EpisodeDraft(
				"Line one\nline two",
				"Summary",
				List.of(new Range(1, 2)),
				List.of("jpm", "not a ticker", "JPM", "BAC"),
				List.of("financials", "Fintech"),
				List.of("  dividends  ", "", "x".repeat(200)),
				List.of("advised holding off"));

		Episode episode = validator.validate(List.of(messy), conversation(4), "u", "s1").get(0);

		assertThat(episode.title()).isEqualTo("Line one line two");
		assertThat(episode.tickers()).containsExactly("JPM", "BAC");
		assertThat(episode.sectors()).containsExactly("Financials");
		assertThat(episode.topics()).hasSize(2).first().isEqualTo("dividends");
		assertThat(episode.topics().get(1)).hasSizeLessThanOrEqualTo(60);
		assertThat(episode.decisions()).containsExactly("advised holding off");
	}

	@Test
	void whenNothingSurvives_oneFallbackEpisodeCoversTheWholeConversation() {
		List<Episode> episodes = validator.validate(List.of(draft("Broken", new Range(40, 50))), conversation(6), "u", "s1");

		assertThat(episodes).singleElement().satisfies(e -> {
			assertThat(e.id()).isEqualTo("s1__0");
			assertThat(e.ranges()).containsExactly(new MessageRange(1, 6));
			assertThat(e.title()).startsWith("Conversation on 1 Oct 2026");
			assertThat(e.summary()).isEqualTo("message 1");
		});
	}

	@Test
	void noDraftsMeansNoEpisodes_notAFallback() {
		assertThat(validator.validate(List.of(), conversation(6), "u", "s1")).isEmpty();
	}
}
