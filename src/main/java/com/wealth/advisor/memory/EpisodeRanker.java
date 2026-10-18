package com.wealth.advisor.memory;

import com.wealth.advisor.config.AdvisorProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Ranks candidate episodes against a search with a hybrid score:
 *
 * <pre>
 * score = similarityWeight × similarity + entityWeight × entityMatch + recencyWeight × recency
 * </pre>
 *
 * <ul>
 *   <li><b>similarity</b>: the cosine of the query and episode vectors, rescaled so that
 *       {@code similarity-floor} and below is 0 and {@code similarity-ceiling} and above is 1 (embedding
 *       models score unrelated text well above zero);
 *   <li><b>entityMatch</b>: the share of the tickers the caller named that the episode mentions (exact);
 *   <li><b>recency</b>: halves every {@code recency-half-life-days}, from the episode's end time.
 * </ul>
 *
 * When the caller named no tickers the entity term is left out and the rest is rescaled, so scores stay on
 * a 0 to 1 scale. An episode is only returned if its <em>relevance</em> (the score without recency) is at
 * least {@code min-score}: being recent must never rescue an unrelated episode. At most
 * {@code max-search-results} are returned, best first.
 */
@Component
public class EpisodeRanker {

	public record Scored(Episode episode, double score, double similarity) {}

	private final double similarityWeight;
	private final double entityWeight;
	private final double recencyWeight;
	private final double halfLifeDays;
	private final double minScore;
	private final int limit;
	private final double floor;
	private final double ceiling;

	public EpisodeRanker(AdvisorProperties properties) {
		AdvisorProperties.Episodes e = properties.episodes();
		if (e.similarityCeiling() <= e.similarityFloor()) {
			throw new IllegalArgumentException("advisor.episodes.similarity-ceiling must be above similarity-floor");
		}
		this.similarityWeight = e.similarityWeight();
		this.entityWeight = e.entityWeight();
		this.recencyWeight = e.recencyWeight();
		this.halfLifeDays = e.recencyHalfLifeDays();
		this.minScore = e.minScore();
		this.limit = e.maxSearchResults();
		this.floor = e.similarityFloor();
		this.ceiling = e.similarityCeiling();
	}

	public List<Scored> rank(List<Episode> candidates, double[] query, List<String> tickers, Instant now) {
		Set<String> wanted = tickers.stream().map(t -> t.strip().toUpperCase(Locale.ROOT)).filter(t -> !t.isEmpty()).collect(Collectors.toSet());
		return candidates.stream()
				.map(e -> score(e, query, wanted, now))
				.flatMap(Optional::stream)
				.sorted(Comparator.comparingDouble(Scored::score).reversed())
				.limit(limit)
				.toList();
	}

	private Optional<Scored> score(Episode episode, double[] query, Set<String> wantedTickers, Instant now) {
		double similarity = clamp01((cosine(query, episode.embedding()) - floor) / (ceiling - floor));
		double recency = recency(episode.endTs(), now);
		double relevance;
		double score;
		if (wantedTickers.isEmpty()) {
			relevance = similarity;
			score = (similarityWeight * similarity + recencyWeight * recency) / (similarityWeight + recencyWeight);
		} else {
			long matched = episode.tickers().stream().filter(wantedTickers::contains).count();
			double entity = (double) matched / wantedTickers.size();
			relevance = (similarityWeight * similarity + entityWeight * entity) / (similarityWeight + entityWeight);
			score = (similarityWeight * similarity + entityWeight * entity + recencyWeight * recency)
					/ (similarityWeight + entityWeight + recencyWeight);
		}
		return relevance >= minScore ? Optional.of(new Scored(episode, score, similarity)) : Optional.empty();
	}

	private double recency(Instant end, Instant now) {
		double ageDays = Math.max(0, Duration.between(end, now).toSeconds() / 86_400.0);
		return Math.pow(0.5, ageDays / halfLifeDays);
	}

	private static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}

	static double cosine(double[] a, double[] b) {
		if (a.length != b.length || a.length == 0) {
			return 0;
		}
		double dot = 0;
		double normA = 0;
		double normB = 0;
		for (int i = 0; i < a.length; i++) {
			dot += a[i] * b[i];
			normA += a[i] * a[i];
			normB += b[i] * b[i];
		}
		return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
	}
}
