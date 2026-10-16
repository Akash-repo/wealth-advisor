package com.wealth.advisor.util;

import com.google.api.core.ApiFuture;
import java.util.concurrent.ExecutionException;

/**
 * Waits for a Firestore call and turns its two checked failure modes into one unchecked
 * exception. Every Firestore access in this app needs the same try/catch (restore the interrupt
 * flag, wrap the cause); this is that block, written once.
 */
public final class FirestoreFutures {

	private FirestoreFutures() {}

	/**
	 * @param action what was being attempted, phrased to follow "Failed to ", e.g. {@code "save
	 *     statement"}
	 */
	public static <T> T await(ApiFuture<T> future, String action) {
		try {
			return future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Failed to " + action, e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("Failed to " + action, e);
		}
	}
}
