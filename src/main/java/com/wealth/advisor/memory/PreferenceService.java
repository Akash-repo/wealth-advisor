package com.wealth.advisor.memory;

import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.memory.PreferenceEntry.Source;
import com.wealth.advisor.memory.PreferenceEntry.Status;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * What the user can do with their own profile from the preferences panel: see it, add an entry, delete
 * one. Everything typed in is validated by the same {@link PreferenceValues} rules the extractor is held
 * to. A delete is a soft delete, and both it and an add count as evidence at the time they happen, so an
 * older conversation can never undo what the user just did (see {@link PreferenceReconciler}).
 */
@Service
public class PreferenceService {

	/** The value was empty or not valid for its category, or the profile is full. */
	public static class InvalidPreferenceException extends RuntimeException {
		public InvalidPreferenceException(String message) {
			super(message);
		}
	}

	/** No active entry with that id belongs to this user. */
	public static class PreferenceNotFoundException extends RuntimeException {
		public PreferenceNotFoundException(String id) {
			super("No such preference: " + id);
		}
	}

	private final PreferenceStore store;
	private final Clock clock;
	private final int maxEntries;

	public PreferenceService(PreferenceStore store, Clock clock, AdvisorProperties properties) {
		this.store = store;
		this.clock = clock;
		this.maxEntries = properties.profile().maxEntries();
	}

	/** The user's active entries, grouped by category in the usual order, most recently confirmed first. */
	public List<PreferenceEntry> active(String userId) {
		return store.load(userId).active().stream()
				.sorted(Comparator.comparing(PreferenceEntry::category).thenComparing(PreferenceEntry::lastConfirmed, Comparator.reverseOrder()))
				.toList();
	}

	public PreferenceEntry add(String userId, PreferenceCategory category, String rawValue) {
		String value = PreferenceValues.normalize(category, rawValue)
				.orElseThrow(() -> new InvalidPreferenceException("That is not a valid value for " + category.title() + "."));
		if (store.load(userId).active().size() >= maxEntries && store.load(userId).find(category, value).filter(PreferenceEntry::isActive).isEmpty()) {
			throw new InvalidPreferenceException("Your profile is full; delete something first.");
		}
		Instant now = clock.instant();
		PreferenceProfile saved = store.update(userId, profile -> {
			Optional<PreferenceEntry> existing = profile.find(category, value);
			if (existing.isPresent() && existing.get().isActive()) {
				return profile;
			}
			Instant firstSeen = existing.map(PreferenceEntry::firstSeen).orElse(now);
			return profile.with(new PreferenceEntry(category, value, Status.ACTIVE, Source.MANUAL, firstSeen, now, now, null, null));
		});
		return saved.find(category, value).orElseThrow();
	}

	public void remove(String userId, String entryId) {
		boolean exists = store.load(userId).active().stream().anyMatch(e -> e.id().equals(entryId));
		if (!exists) {
			throw new PreferenceNotFoundException(entryId);
		}
		Instant now = clock.instant();
		store.update(userId, profile -> {
			PreferenceProfile updated = profile;
			for (PreferenceEntry entry : profile.active()) {
				if (entry.id().equals(entryId)) {
					updated = updated.with(entry.withStatus(Status.REMOVED_BY_USER, now));
				}
			}
			return updated;
		});
	}
}
