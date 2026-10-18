package com.wealth.advisor.memory;

import java.util.Locale;

/**
 * A deterministic stand-in for the embedding model: a vector with one slot per word in a tiny
 * vocabulary, so texts that share words are similar and unrelated texts are not. Enough to test
 * ranking and the job without a model call.
 */
final class FakeEmbedder implements Embedder {

	static final String[] VOCABULARY = {"jpmorgan", "bank", "dividend", "retirement", "retire", "rsu", "valuation", "tech", "energy", "index"};

	@Override
	public double[] embedDocument(String text) {
		return vector(text);
	}

	@Override
	public double[] embedQuery(String text) {
		return vector(text);
	}

	static double[] vector(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		double[] v = new double[VOCABULARY.length];
		for (int i = 0; i < VOCABULARY.length; i++) {
			v[i] = lower.contains(VOCABULARY[i]) ? 1 : 0;
		}
		return v;
	}
}
