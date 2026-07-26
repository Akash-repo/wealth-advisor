package com.wealth.advisor.portfolio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Splits extracted PDF text into ~250-word chunks for embedding, per the design doc's FR-2. */
final class TextChunker {

	private static final int WORDS_PER_CHUNK = 250;

	private TextChunker() {
	}

	static List<String> chunk(String text) {
		String[] words = text.trim().split("\\s+");
		List<String> chunks = new ArrayList<>();
		for (int start = 0; start < words.length; start += WORDS_PER_CHUNK) {
			int end = Math.min(start + WORDS_PER_CHUNK, words.length);
			chunks.add(String.join(" ", Arrays.asList(words).subList(start, end)));
		}
		return chunks;
	}
}
