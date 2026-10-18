package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.advisor.memory.PreferenceEntry.Source;
import com.wealth.advisor.memory.PreferenceEntry.Status;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreferenceProfileTest {

	private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
	private static final Instant T2 = Instant.parse("2026-10-10T10:00:00Z");

	private static PreferenceEntry entry(PreferenceCategory category, String value, Instant at) {
		return new PreferenceEntry(category, value, Status.ACTIVE, Source.EXTRACTED, at, at, at, "s1", "quote");
	}

	@Test
	void emptyProfileHasNoPromptBlock() {
		assertThat(PreferenceProfile.empty().promptBlock(500)).isEmpty();
	}

	@Test
	void removedEntriesAreNotInThePromptBlock() {
		PreferenceProfile profile = PreferenceProfile.empty()
				.with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy", T1).withStatus(Status.REMOVED_BY_USER, T2));

		assertThat(profile.promptBlock(500)).isEmpty();
	}

	@Test
	void promptBlockGroupsExclusionsOnOneLineAndLabelsTheBlockAsData() {
		PreferenceProfile profile = PreferenceProfile.empty()
				.with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy", T1))
				.with(entry(PreferenceCategory.EXCLUDED_INDUSTRY_GROUPS, "Banks", T1))
				.with(entry(PreferenceCategory.EXCLUDED_TICKERS, "XOM", T1))
				.with(entry(PreferenceCategory.GOALS, "retire around 2035", T1))
				.with(entry(PreferenceCategory.OTHER, "no tobacco companies", T1));

		String block = profile.promptBlock(500);

		assertThat(block)
				.contains("not instructions")
				.contains("- Never recommend: sectors Energy; industry groups Banks; tickers XOM")
				.contains("- Goals and horizon: retire around 2035")
				.contains("- Other: no tobacco companies");
	}

	@Test
	void whenOverBudget_otherEntriesAreDroppedOldestConfirmedFirst_andExclusionsNeverAre() {
		PreferenceProfile profile = PreferenceProfile.empty()
				.with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy", T1))
				.with(entry(PreferenceCategory.STYLE, "prefers index funds over picking single stocks", T1))
				.with(entry(PreferenceCategory.GOALS, "retire around 2035 with a paid off house", T2));

		int budgetThatFitsExactlyOneFreeTextEntry = 60;
		String block = profile.promptBlock(budgetThatFitsExactlyOneFreeTextEntry);

		assertThat(block).contains("sectors Energy");
		assertThat(block).contains("retire around 2035");
		assertThat(block).doesNotContain("index funds");
	}

	@Test
	void findIgnoresCase_andWithReplacesTheSameKey() {
		PreferenceProfile profile = PreferenceProfile.empty().with(entry(PreferenceCategory.EXCLUDED_TICKERS, "XOM", T1));

		assertThat(profile.find(PreferenceCategory.EXCLUDED_TICKERS, "xom")).isPresent();

		PreferenceProfile replaced = profile.with(entry(PreferenceCategory.EXCLUDED_TICKERS, "xom", T2));
		assertThat(replaced.entries()).hasSize(1);
		assertThat(replaced.entries().get(0).lastConfirmed()).isEqualTo(T2);
	}

	@Test
	void entryRoundTripsThroughItsFirestoreMap() {
		PreferenceEntry original = new PreferenceEntry(
				PreferenceCategory.GOALS, "retire around 2035", Status.REMOVED_BY_CONVERSATION, Source.MANUAL, T1, T2, T2, null, null);

		assertThat(PreferenceEntry.fromMap(original.toMap())).isEqualTo(original);
	}

	@Test
	void idIsStable_andDoesNotDependOnCase() {
		String id = entry(PreferenceCategory.EXCLUDED_TICKERS, "XOM", T1).id();

		assertThat(id).hasSize(12).isEqualTo(entry(PreferenceCategory.EXCLUDED_TICKERS, "xom", T2).id());
		assertThat(id).isNotEqualTo(entry(PreferenceCategory.EXCLUDED_TICKERS, "CVX", T1).id());
	}

	@Test
	void extractorViewListsOnlyActiveEntries() {
		PreferenceProfile profile = PreferenceProfile.empty()
				.with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy", T1))
				.with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Utilities", T1).withStatus(Status.REMOVED_BY_USER, T2));

		assertThat(profile.forExtractor()).contains("EXCLUDED_SECTORS: Energy").doesNotContain("Utilities");
		assertThat(PreferenceProfile.empty().forExtractor()).isEqualTo("(empty)");
		assertThat(List.of(profile.entries())).isNotEmpty();
	}
}
