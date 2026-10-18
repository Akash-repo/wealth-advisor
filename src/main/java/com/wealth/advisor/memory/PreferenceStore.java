package com.wealth.advisor.memory;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.wealth.advisor.constants.FirestoreCollections;
import com.wealth.advisor.constants.FirestoreFields;
import com.wealth.advisor.util.FirestoreFutures;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * Firestore access for the preference profile: one {@code user_preferences} document per user,
 * holding all of that user's entries (active and removed) in an {@code entries} list.
 */
@Component
public class PreferenceStore {

	private static final String ENTRIES = "entries";

	private final Firestore firestore;

	public PreferenceStore(Firestore firestore) {
		this.firestore = firestore;
	}

	public PreferenceProfile load(String userId) {
		return fromSnapshot(FirestoreFutures.await(document(userId).get(), "read preferences"));
	}

	/**
	 * Applies {@code change} to the user's current profile and saves the result, inside a transaction so
	 * two overlapping runs (two tabs, two instances) cannot overwrite each other's entries.
	 */
	public PreferenceProfile update(String userId, UnaryOperator<PreferenceProfile> change) {
		DocumentReference document = document(userId);
		return FirestoreFutures.await(
				firestore.runTransaction(transaction -> {
					PreferenceProfile updated = change.apply(fromSnapshot(transaction.get(document).get()));
					transaction.set(document, toMap(userId, updated));
					return updated;
				}),
				"update preferences");
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
