package com.wealth.advisor.memory;

import com.wealth.advisor.config.AdvisorProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Finds a user's past episodes for a topic: the candidates from the time window, then the hybrid score
 * from {@link EpisodeRanker}. The similarity search runs in the app over the user's own episodes
 * (a few hundred at most), which needs no vector index; if that stops being enough, only this class
 * changes (docs/design/05, D19).
 */
@Service
public class EpisodeSearch {

	private final EpisodeStore store;
	private final Embedder embedder;
	private final EpisodeRanker ranker;
	private final Clock clock;
	private final int defaultWindowDays;

	public EpisodeSearch(EpisodeStore store, Embedder embedder, EpisodeRanker ranker, Clock clock, AdvisorProperties properties) {
		this.store = store;
		this.embedder = embedder;
		this.ranker = ranker;
		this.clock = clock;
		this.defaultWindowDays = properties.episodes().defaultWindowDays();
	}

	public List<EpisodeRanker.Scored> search(String userId, String topic, List<String> tickers, SearchWindow window) {
		Instant now = clock.instant();
		List<Episode> candidates = store.candidates(userId, window.since(now, defaultWindowDays));
		if (candidates.isEmpty()) {
			return List.of();
		}
		return ranker.rank(candidates, embedder.embedQuery(topic), tickers, now);
	}
}
