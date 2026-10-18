package com.wealth.advisor.memory;

import java.time.Instant;
import java.util.List;

/**
 * A topic from one past conversation, summarised for later recall. The original messages stay in the
 * session store; an episode only points at them ({@code sessionId} plus {@code ranges}). A topic the
 * conversation came back to is one episode with several ranges. Ranges of different episodes never
 * overlap, and an episode never spans sessions. {@code embedding} is the vector of title plus summary,
 * used for the similarity part of search. See docs/design/04-episodic-memory.md.
 */
public record Episode(
		String id,
		String userId,
		String sessionId,
		List<MessageRange> ranges,
		Instant startTs,
		Instant endTs,
		String title,
		String summary,
		List<String> tickers,
		List<String> sectors,
		List<String> topics,
		List<String> decisions,
		double[] embedding) {

	public Episode withEmbedding(double[] vector) {
		return new Episode(id, userId, sessionId, ranges, startTs, endTs, title, summary, tickers, sectors, topics, decisions, vector);
	}

	/** The text that is embedded: what a person would search by. */
	public String embeddingText() {
		return title + "\n" + summary;
	}
}
