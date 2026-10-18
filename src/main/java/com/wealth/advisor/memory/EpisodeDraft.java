package com.wealth.advisor.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * What the episode extractor model proposes for one topic, before {@link EpisodeValidator} checks it:
 * nothing here is trusted yet (ranges may be out of bounds or overlap, lists may hold anything).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EpisodeDraft(
		String title,
		String summary,
		List<Range> ranges,
		List<String> tickers,
		List<String> sectors,
		List<String> topics,
		List<String> decisions) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Range(int from, int to) {}
}
