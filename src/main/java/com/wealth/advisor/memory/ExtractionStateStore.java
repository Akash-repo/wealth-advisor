package com.wealth.advisor.memory;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.wealth.advisor.constants.FirestoreCollections;
import com.wealth.advisor.constants.FirestoreFields;
import com.wealth.advisor.util.FirestoreFutures;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Remembers, per (user, session), the session's last-update time as of when the memory job last
 * processed it. A session whose current last-update time is no later than that is skipped. Kept in its
 * own collection rather than on ADK's session documents, which {@code FirestoreSessionService} owns.
 */
@Component
public class ExtractionStateStore {

	private static final String SESSION_ID = "sessionId";
	private static final String PROCESSED_UPDATE_TIME = "processedUpdateTime";

	private final Firestore firestore;

	public ExtractionStateStore(Firestore firestore) {
		this.firestore = firestore;
	}

	/** Session id to the last-update time that was processed, for every session of the user seen so far. */
	public Map<String, Instant> processed(String userId) {
		Map<String, Instant> processed = new HashMap<>();
		var query = firestore.collection(FirestoreCollections.MEMORY_EXTRACTION_STATE).whereEqualTo(FirestoreFields.USER_ID, userId);
		for (QueryDocumentSnapshot doc : FirestoreFutures.await(query.get(), "read memory extraction state").getDocuments()) {
			Timestamp t = doc.getTimestamp(PROCESSED_UPDATE_TIME);
			if (t != null) {
				processed.put(doc.getString(SESSION_ID), Instant.ofEpochSecond(t.getSeconds(), t.getNanos()));
			}
		}
		return processed;
	}

	public void markProcessed(String userId, String sessionId, Instant lastUpdate) {
		Map<String, Object> doc = Map.of(
				FirestoreFields.USER_ID, userId,
				SESSION_ID, sessionId,
				PROCESSED_UPDATE_TIME, Timestamp.ofTimeSecondsAndNanos(lastUpdate.getEpochSecond(), lastUpdate.getNano()),
				"processedAt", Timestamp.now());
		FirestoreFutures.await(
				firestore.collection(FirestoreCollections.MEMORY_EXTRACTION_STATE).document(id(userId, sessionId)).set(doc),
				"save memory extraction state");
	}

	public void delete(String userId, String sessionId) {
		FirestoreFutures.await(
				firestore.collection(FirestoreCollections.MEMORY_EXTRACTION_STATE).document(id(userId, sessionId)).delete(),
				"delete memory extraction state");
	}

	private static String id(String userId, String sessionId) {
		return userId + "__" + sessionId;
	}
}
