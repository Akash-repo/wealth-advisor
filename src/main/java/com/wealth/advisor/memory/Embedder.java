package com.wealth.advisor.memory;

/**
 * Turns text into a vector for similarity search. Document and query vectors come from the same model
 * but are asked for differently, which embedding models are trained to expect. {@link GenAiEmbedder} is
 * the real one; tests use a fake.
 */
public interface Embedder {

	double[] embedDocument(String text);

	double[] embedQuery(String text);
}
