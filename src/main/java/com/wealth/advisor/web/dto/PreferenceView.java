package com.wealth.advisor.web.dto;

import com.wealth.advisor.memory.PreferenceEntry;
import java.time.Instant;

/** One preference as the panel shows it; {@code source} is "extracted" (learned from a chat) or "manual". */
public record PreferenceView(String id, String category, String categoryTitle, String value, String source, Instant lastConfirmed) {

	public static PreferenceView of(PreferenceEntry entry) {
		return new PreferenceView(
				entry.id(),
				entry.category().name(),
				entry.category().title(),
				entry.value(),
				entry.source().name().toLowerCase(),
				entry.lastConfirmed());
	}
}
