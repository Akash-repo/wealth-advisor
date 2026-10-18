package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.wealth.advisor.agent.prompt.PromptLoader;
import com.wealth.advisor.config.TestProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The episode extractor and validator together against the real model, on the kinds of conversation the
 * design cares about: topics that are interleaved and come back (valuation, retirement, RSUs, valuation
 * again), a conversation that is only small talk, and a single short topic. Skips itself without Gemini
 * credentials. Tagged {@code eval}.
 */
@Tag("eval")
class EpisodeExtractorEvalTest {

	private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

	private static EpisodeExtractor extractor;
	private static final EpisodeValidator VALIDATOR = new EpisodeValidator(TestProperties.defaults());

	@BeforeAll
	static void connect() {
		assumeTrue(
				"true".equalsIgnoreCase(System.getenv("GOOGLE_GENAI_USE_VERTEXAI"))
						|| System.getenv("GOOGLE_API_KEY") != null
						|| System.getenv("GEMINI_API_KEY") != null,
				"no Gemini credentials configured");
		extractor = new EpisodeExtractor(Client.builder().build(), TestProperties.defaults(), new ObjectMapper(), new PromptLoader());
	}

	/** Alternating user and assistant lines, starting with the user. */
	private static List<TimedMessage> conversation(String... lines) {
		List<TimedMessage> messages = new ArrayList<>();
		for (int i = 0; i < lines.length; i++) {
			messages.add(new TimedMessage(i + 1, i % 2 == 0 ? TimedMessage.USER : TimedMessage.AGENT, T0.plusSeconds(60L * i), lines[i]));
		}
		return messages;
	}

	private static List<Episode> episodesOf(List<TimedMessage> messages) {
		return VALIDATOR.validate(extractor.extract(messages), messages, "u", "s1");
	}

	private static boolean mentions(Episode e, String word) {
		return (e.title() + " " + e.summary()).toLowerCase(Locale.ROOT).contains(word);
	}

	@Test
	void interleavedTopicsThatComeBackBecomeOneEpisodePerTopicWithSeveralRanges() {
		List<TimedMessage> messages = conversation(
				"Is NVDA expensive compared with other tech stocks right now?",
				"NVDA trades at a high forward P/E of about 35, richer than the tech average of around 25, though its earnings growth is far faster.",
				"What about MSFT on the same measure?",
				"MSFT trades near 30 times forward earnings, so cheaper than NVDA but above the sector average.",
				"Switching topics: how much should I have saved for retirement by 40?",
				"A common rule of thumb is about three times your annual salary saved by 40, assuming you plan to retire around 65.",
				"And should I be putting that mostly in stocks at my age?",
				"At 40 with 25 years to go, many people hold roughly 70 to 80 percent in stocks, adjusting for how well you tolerate drops.",
				"Separately, my employer gives me RSUs that vest next month. How are they taxed?",
				"RSUs are taxed as ordinary income at the market value on the vesting date, and usually part of the shares is withheld for tax.",
				"Should I sell them right when they vest?",
				"Many advisors suggest selling at vesting to avoid concentration in one company, since the tax is already due.",
				"Going back to valuation: what does PEG say about NVDA?",
				"NVDA's PEG ratio is around 1.2, which suggests the growth is largely priced in but not extreme.",
				"Thanks, that's all for now.",
				"You're welcome.");

		List<Episode> episodes = episodesOf(messages);

		assertThat(episodes).as("one episode per topic").hasSizeBetween(3, 4);
		Episode valuation = episodes.stream().filter(e -> mentions(e, "valuation") || mentions(e, "nvda")).findFirst().orElseThrow();
		assertThat(valuation.ranges()).as("the valuation topic that comes back is one episode with two stretches").hasSizeGreaterThanOrEqualTo(2);
		assertThat(valuation.ranges().get(0).from()).isEqualTo(1);
		assertThat(valuation.ranges().get(valuation.ranges().size() - 1).to()).isGreaterThanOrEqualTo(14);
		assertThat(episodes).anyMatch(e -> mentions(e, "retire"));
		assertThat(episodes).anyMatch(e -> mentions(e, "rsu"));
		assertThat(episodes.stream().flatMap(e -> e.tickers().stream()).toList()).as("no invented tickers").isSubsetOf(Set.of("NVDA", "MSFT"));
		assertThat(episodes).allSatisfy(e -> assertThat(e.summary()).isNotBlank());
	}

	@Test
	void smallTalkAloneProducesNoEpisodes() {
		assertThat(episodesOf(conversation("Hi there!", "Hello! How can I help you today?", "Just saying hello, thanks.", "Anytime!"))).isEmpty();
	}

	@Test
	void aSingleTopicIsOneEpisodeWithTheAdviceThatWasGiven() {
		List<Episode> episodes = episodesOf(conversation(
				"Should I add to my JPM position? It has a good dividend.",
				"JPM yields about 2.3 percent, but you already hold a lot of financials, so I would hold off on adding more for now.",
				"Ok, I'll hold off."));

		assertThat(episodes).hasSize(1);
		assertThat(episodes.get(0).tickers()).contains("JPM");
		assertThat(String.join(" ", episodes.get(0).decisions()).toLowerCase(Locale.ROOT)).containsAnyOf("hold", "wait", "not add", "hold off");
	}
}
