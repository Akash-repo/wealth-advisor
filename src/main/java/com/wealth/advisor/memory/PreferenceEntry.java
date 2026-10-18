package com.wealth.advisor.memory;

import com.google.cloud.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/**
 * One fact in a user's preference profile. Entries are never physically removed: a withdrawn or
 * deleted one keeps its record with a non-{@link Status#ACTIVE} status.
 *
 * <p>{@code evidenceAt} is the time of the user message (or UI edit) that last set this entry's
 * status. A later piece of evidence may change the entry; an older one may not — which is what makes
 * "newer overrides older" hold no matter in which order old conversations are reprocessed.
 */
public record PreferenceEntry(
		PreferenceCategory category,
		String value,
		Status status,
		Source source,
		Instant firstSeen,
		Instant lastConfirmed,
		Instant evidenceAt,
		String sessionId,
		String evidenceQuote) {

	public enum Status {
		ACTIVE,
		/** Deleted in the UI (a soft delete: the record stays). */
		REMOVED_BY_USER,
		/** Withdrawn by the user in a conversation ("I'm okay with energy stocks now"). */
		REMOVED_BY_CONVERSATION
	}

	public enum Source {
		/** Learned from a conversation by the extractor. */
		EXTRACTED,
		/** Typed in by the user in the preferences panel; never overridden by the extractor. */
		MANUAL
	}

	private static final int ID_LENGTH = 12;

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	/** Identity of the fact: the same category and value, regardless of case, is the same entry. */
	public String key() {
		return keyOf(category, value);
	}

	public static String keyOf(PreferenceCategory category, String value) {
		return category.name() + "|" + value.toLowerCase(Locale.ROOT);
	}

	/** A short stable id derived from {@link #key()}, safe to put in a URL. */
	public String id() {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(key().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, ID_LENGTH);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
		}
	}

	public PreferenceEntry withStatus(Status newStatus, Instant at) {
		return new PreferenceEntry(category, value, newStatus, source, firstSeen, lastConfirmed, at, sessionId, evidenceQuote);
	}

	/** Confirmed again by newer evidence; reactivates the entry if it was removed. */
	public PreferenceEntry confirmedAt(Instant at, String newSessionId, String newQuote) {
		return new PreferenceEntry(category, value, Status.ACTIVE, source, firstSeen, at, at, newSessionId, newQuote);
	}

	Map<String, Object> toMap() {
		Map<String, Object> map = new HashMap<>();
		map.put("category", category.name());
		map.put("value", value);
		map.put("status", status.name());
		map.put("source", source.name());
		map.put("firstSeen", toTimestamp(firstSeen));
		map.put("lastConfirmed", toTimestamp(lastConfirmed));
		map.put("evidenceAt", toTimestamp(evidenceAt));
		map.put("sessionId", sessionId);
		map.put("evidenceQuote", evidenceQuote);
		return map;
	}

	static PreferenceEntry fromMap(Map<String, Object> map) {
		return new PreferenceEntry(
				PreferenceCategory.valueOf((String) map.get("category")),
				(String) map.get("value"),
				Status.valueOf((String) map.get("status")),
				Source.valueOf((String) map.get("source")),
				toInstant(map.get("firstSeen")),
				toInstant(map.get("lastConfirmed")),
				toInstant(map.get("evidenceAt")),
				(String) map.get("sessionId"),
				(String) map.get("evidenceQuote"));
	}

	private static Timestamp toTimestamp(Instant instant) {
		return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
	}

	private static Instant toInstant(Object timestamp) {
		Timestamp t = (Timestamp) timestamp;
		return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
	}
}
