package com.wealth.advisor.memory;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.wealth.advisor.constants.FirestoreCollections;
import com.wealth.advisor.constants.FirestoreFields;
import com.wealth.advisor.util.FirestoreFutures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * Firestore access for the preference profile: one {@code user_preferences} document per user,
 * holding all of that user's entries (active and removed) in an {@code entries} list.
 *
 * <p>{@link #load} is called on every model call of every turn (to build the prompt block), so a loaded
 * profile is kept in memory for a few seconds. Writes through this store refresh the cache at once; a
 * change made on another instance shows up within the cache time.
 */
@Component
public class PreferenceStore {

	private static final String ENTRIES = "entries";

	private static final Duration CACHE_TIME = Duration.ofSeconds(15);

	private record Cached(PreferenceProfile profile, Instant expiresAt) {}

	private final Firestore firestore;
	private final Clock clock;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public PreferenceStore(Firestore firestore, Clock clock) {
		this.firestore = firestore;
		this.clock = clock;
	}

	public PreferenceProfile load(String userId) {
		Cached cached = cache.get(userId);
		if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
			return cached.profile();
		}
		return remember(userId, fromSnapshot(FirestoreFutures.await(document(userId).get(), "read preferences")));
	}

	/**
	 * Applies {@code change} to the user's current profile and saves the result, inside a transaction so
	 * two overlapping runs (two tabs, two instances) cannot overwrite each other's entries.
	 */
	public PreferenceProfile update(String userId, UnaryOperator<PreferenceProfile> change) {
		DocumentReference document = document(userId);
		return remember(
				userId,
				FirestoreFutures.await(
						firestore.runTransaction(transaction -> {
							PreferenceProfile updated = change.apply(fromSnapshot(transaction.get(document).get()));
							transaction.set(document, toMap(userId, updated));
							return updated;
						}),
						"update preferences"));
	}

	private PreferenceProfile remember(String userId, PreferenceProfile profile) {
		cache.put(userId, new Cached(profile, clock.instant().plus(CACHE_TIME)));
		return profile;
	}

	private DocumentReference document(String userId) {
		return firestore.collection(FirestoreCollections.USER_PREFERENCES).document(userId);
	}

	@SuppressWarnings("unchecked")
	private static PreferenceProfile fromSnapshot(DocumentSnapshot snapshot) {
		if (!snapshot.exists()) {
			return PreferenceProfile.empty();
		}
		List<Map<String, Object>> raw = (List<Map<String, Object>>) snapshot.get(ENTRIES);
		return raw == null ? PreferenceProfile.empty() : new PreferenceProfile(raw.stream().map(PreferenceEntry::fromMap).toList());
	}

	private static Map<String, Object> toMap(String userId, PreferenceProfile profile) {
		Map<String, Object> map = new HashMap<>();
		map.put(FirestoreFields.USER_ID, userId);
		map.put(ENTRIES, profile.entries().stream().map(PreferenceEntry::toMap).toList());
		map.put("updatedAt", Timestamp.now());
		return map;
	}
}
