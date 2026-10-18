package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wealth.advisor.config.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EpisodeSearchTest {

	private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

	private EpisodeStore store;
	private Embedder embedder;
	private EpisodeSearch search;

	@BeforeEach
	void setUp() {
		store = mock(EpisodeStore.class);
		embedder = mock(Embedder.class);
		search = new EpisodeSearch(store, embedder, new EpisodeRanker(TestProperties.defaults()), Clock.fixed(NOW, ZoneOffset.UTC), TestProperties.defaults());
	}

	private static Episode episode(String id, String text) {
		return new Episode(id, "u", "s", List.of(new MessageRange(1, 2)), NOW.minusSeconds(7200), NOW.minusSeconds(3600), text, text, List.of(), List.of(), List.of(), List.of(), FakeEmbedder.vector(text));
	}

	@Test
	void theWindowDecidesWhichEpisodesAreFetched() {
		when(store.candidates(eq("u"), any())).thenReturn(List.of());

		search.search("u", "anything", List.of(), SearchWindow.PAST_MONTH);

		verify(store).candidates("u", Instant.parse("2026-09-30T12:00:00Z"));
	}

	@Test
	void anyMeansTheConfiguredDefaultOfTwelveMonths() {
		when(store.candidates(eq("u"), any())).thenReturn(List.of());

		search.search("u", "anything", List.of(), SearchWindow.ANY);

		verify(store).candidates("u", NOW.minus(java.time.Duration.ofDays(365)));
	}

	@Test
	void noCandidatesMeansNoEmbeddingCall() {
		when(store.candidates(eq("u"), any())).thenReturn(List.of());

		assertThat(search.search("u", "anything", List.of(), SearchWindow.ANY)).isEmpty();

		verify(embedder, never()).embedQuery(any());
	}

	@Test
	void candidatesAreRankedAgainstTheEmbeddedTopic() {
		when(store.candidates(eq("u"), any())).thenReturn(List.of(episode("ret", "retirement retire"), episode("bank", "jpmorgan bank dividend")));
		when(embedder.embedQuery("the bank idea")).thenReturn(FakeEmbedder.vector("jpmorgan bank"));

		List<EpisodeRanker.Scored> hits = search.search("u", "the bank idea", List.of(), SearchWindow.ANY);

		assertThat(hits).extracting(h -> h.episode().id()).containsExactly("bank");
	}
}
