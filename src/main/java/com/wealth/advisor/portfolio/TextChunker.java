package com.wealth.advisor.portfolio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits extracted PDF text into ~250-word chunks for embedding, per the design doc's FR-2.
 *
 * <p>Why chunk at all: embedding models turn a whole piece of text into a single fixed-size
 * vector, so a very long document embedded as one blob would lose most of its detail (and often
 * exceeds the model's input limit anyway). Splitting into small chunks first means each chunk's
 * vector stays specific enough that a vector search can find "the paragraph about Nvidia
 * holdings" rather than just "the whole 10-page portfolio statement".
 *
 * <pre>{@code
 * List<String> chunks = TextChunker.chunk(extractedPdfText);
 * // chunks.get(0), chunks.get(1), ... each ~250 words, in original order
 * }</pre>
 *
 * <p>This is intentionally simple — a fixed word count, no sentence-boundary awareness or
 * overlap between chunks. Good enough for a learning project; a production RAG pipeline would
 * likely chunk on sentence/paragraph boundaries and overlap chunks slightly so a fact split
 * across a chunk boundary isn't lost entirely.
 */
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
