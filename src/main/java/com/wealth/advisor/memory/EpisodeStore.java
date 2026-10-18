package com.wealth.advisor.memory;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.VectorValue;
import com.wealth.advisor.constants.FirestoreCollections;
import com.wealth.advisor.constants.FirestoreFields;
import com.wealth.advisor.util.FirestoreFutures;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Firestore access for the {@code episodes} collection: one document per {@link Episode}, with
 * predictable ids (session id plus position) so that regenerating a session overwrites its episodes
 * instead of duplicating them. The vector is stored as a Firestore vector value, so the collection can
 * be given a vector index later without a migration.
 *
 * <p>Queries filter on {@code userId} (and {@code sessionId}) by equality only, which needs no composite
 * index; the time window is applied in Java, which is fine for the few hundred episodes a user has.
 */
@Component
public class EpisodeStore {

	private static final String SESSION_ID = "sessionId";
	private static final String END_TS = "endTs";
	private static final String EMBEDDING = "embedding";

	private final Firestore firestore;

	public EpisodeStore(Firestore firestore) {
		this.firestore = firestore;
	}

	/** Makes {@code episodes} exactly the episodes of this session: writes them and removes any leftovers. */
	public void replaceSession(String userId, String sessionId, List<Episode> episodes) {
		Set<String> keep = new HashSet<>();
		var batch = firestore.batch();
		for (Episode episode : episodes) {
			keep.add(episode.id());
			batch.set(document(episode.id()), toMap(episode));
		}
		for (QueryDocumentSnapshot existing : sessionDocuments(userId, sessionId)) {
			if (!keep.contains(existing.getId())) {
				batch.delete(existing.getReference());
			}
		}
		FirestoreFutures.await(batch.commit(), "save episodes");
	}

	public void deleteSession(String userId, String sessionId) {
		var batch = firestore.batch();
		sessionDocuments(userId, sessionId).forEach(d -> batch.delete(d.getReference()));
		FirestoreFutures.await(batch.commit(), "delete episodes");
	}

	/** The user's episodes that ended at or after {@code since}. */
	public List<Episode> candidates(String userId, Instant since) {
		var query = firestore.collection(FirestoreCollections.EPISODES).whereEqualTo(FirestoreFields.USER_ID, userId);
		return FirestoreFutures.await(query.get(), "read episodes").getDocuments().stream()
				.map(EpisodeStore::fromSnapshot)
				.filter(e -> !e.endTs().isBefore(since))
				.toList();
	}

	/** The episode, only if it belongs to {@code userId}. */
	public Optional<Episode> get(String userId, String episodeId) {
		DocumentSnapshot snapshot = FirestoreFutures.await(document(episodeId).get(), "read episode");
		if (!snapshot.exists() || !userId.equals(snapshot.getString(FirestoreFields.USER_ID))) {
			return Optional.empty();
		}
		return Optional.of(fromSnapshot(snapshot));
	}

	private List<QueryDocumentSnapshot> sessionDocuments(String userId, String sessionId) {
		var query = firestore.collection(FirestoreCollections.EPISODES)
				.whereEqualTo(FirestoreFields.USER_ID, userId)
				.whereEqualTo(SESSION_ID, sessionId);
		return FirestoreFutures.await(query.get(), "read session episodes").getDocuments();
	}

	private DocumentReference document(String episodeId) {
		return firestore.collection(FirestoreCollections.EPISODES).document(episodeId);
	}

	private static Map<String, Object> toMap(Episode e) {
		Map<String, Object> map = new HashMap<>();
		map.put(FirestoreFields.USER_ID, e.userId());
		map.put(SESSION_ID, e.sessionId());
		map.put("ranges", e.ranges().stream().map(r -> Map.of("from", r.from(), "to", r.to())).toList());
		map.put("startTs", timestamp(e.startTs()));
		map.put(END_TS, timestamp(e.endTs()));
		map.put("title", e.title());
		map.put("summary", e.summary());
		map.put("tickers", e.tickers());
		map.put("sectors", e.sectors());
		map.put("topics", e.topics());
		map.put("decisions", e.decisions());
		map.put(EMBEDDING, FieldValue.vector(e.embedding()));
		map.put("createdAt", Timestamp.now());
		return map;
	}

	@SuppressWarnings("unchecked")
	private static Episode fromSnapshot(DocumentSnapshot d) {
		List<Map<String, Object>> ranges = (List<Map<String, Object>>) d.get("ranges");
		return new Episode(
				d.getId(),
				d.getString(FirestoreFields.USER_ID),
				d.getString(SESSION_ID),
				ranges.stream().map(r -> new MessageRange(((Number) r.get("from")).intValue(), ((Number) r.get("to")).intValue())).toList(),
				instant(d.getTimestamp("startTs")),
				instant(d.getTimestamp(END_TS)),
				d.getString("title"),
				d.getString("summary"),
				strings(d, "tickers"),
				strings(d, "sectors"),
				strings(d, "topics"),
				strings(d, "decisions"),
				((VectorValue) d.get(EMBEDDING)).toArray());
	}

	@SuppressWarnings("unchecked")
	private static List<String> strings(DocumentSnapshot d, String field) {
		List<String> values = (List<String>) d.get(field);
		return values == null ? List.of() : values;
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
	}

	private static Instant instant(Timestamp t) {
		return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
	}
}
