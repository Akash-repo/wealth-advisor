package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wealth.advisor.config.AdvisorProperties;
import com.wealth.advisor.config.TestProperties;
import com.wealth.advisor.memory.PreferenceEntry.Source;
import com.wealth.advisor.memory.PreferenceEntry.Status;
import com.wealth.advisor.memory.PreferenceService.InvalidPreferenceException;
import com.wealth.advisor.memory.PreferenceService.PreferenceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PreferenceServiceTest {

	private static final Instant EARLIER = Instant.parse("2026-10-01T10:00:00Z");
	private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

	private final AtomicReference<PreferenceProfile> state = new AtomicReference<>(PreferenceProfile.empty());
	private PreferenceService service;

	@BeforeEach
	void setUp() {
		service = serviceWith(TestProperties.defaults());
	}

	private PreferenceService serviceWith(AdvisorProperties properties) {
		PreferenceStore store = mock(PreferenceStore.class);
		when(store.load(anyString())).thenAnswer(invocation -> state.get());
		when(store.update(anyString(), any())).thenAnswer(invocation -> {
			UnaryOperator<PreferenceProfile> change = invocation.getArgument(1);
			PreferenceProfile updated = change.apply(state.get());
			state.set(updated);
			return updated;
		});
		return new PreferenceService(store, Clock.fixed(NOW, ZoneOffset.UTC), properties);
	}

	private static PreferenceEntry extracted(PreferenceCategory category, String value) {
		return new PreferenceEntry(category, value, Status.ACTIVE, Source.EXTRACTED, EARLIER, EARLIER, EARLIER, "s1", "quote");
	}

	@Test
	void aTypedInSectorIsStoredAsAManualActiveEntryDatedNow() {
		PreferenceEntry added = service.add("u", PreferenceCategory.EXCLUDED_SECTORS, "energy");

		assertThat(added.value()).isEqualTo("Energy");
		assertThat(added.source()).isEqualTo(Source.MANUAL);
		assertThat(added.isActive()).isTrue();
		assertThat(added.evidenceAt()).isEqualTo(NOW);
		assertThat(service.active("u")).containsExactly(added);
	}

	@Test
	void anInvalidValueIsRejected() {
		assertThatThrownBy(() -> service.add("u", PreferenceCategory.EXCLUDED_SECTORS, "Fintech")).isInstanceOf(InvalidPreferenceException.class);
		assertThatThrownBy(() -> service.add("u", PreferenceCategory.EXCLUDED_TICKERS, "not a ticker")).isInstanceOf(InvalidPreferenceException.class);
		assertThatThrownBy(() -> service.add("u", PreferenceCategory.OTHER, "  ")).isInstanceOf(InvalidPreferenceException.class);
		assertThat(state.get().entries()).isEmpty();
	}

	@Test
	void addingSomethingAlreadyActiveChangesNothing() {
		state.set(PreferenceProfile.empty().with(extracted(PreferenceCategory.EXCLUDED_SECTORS, "Energy")));

		PreferenceEntry result = service.add("u", PreferenceCategory.EXCLUDED_SECTORS, "Energy");

		assertThat(result.source()).isEqualTo(Source.EXTRACTED);
		assertThat(state.get().entries()).hasSize(1);
	}

	@Test
	void addingSomethingThatWasDeletedBringsItBackAsManualKeepingWhenItWasFirstSeen() {
		state.set(PreferenceProfile.empty().with(extracted(PreferenceCategory.EXCLUDED_SECTORS, "Energy").withStatus(Status.REMOVED_BY_USER, EARLIER)));

		PreferenceEntry result = service.add("u", PreferenceCategory.EXCLUDED_SECTORS, "Energy");

		assertThat(result.isActive()).isTrue();
		assertThat(result.source()).isEqualTo(Source.MANUAL);
		assertThat(result.firstSeen()).isEqualTo(EARLIER);
		assertThat(result.evidenceAt()).isEqualTo(NOW);
	}

	@Test
	void aFullProfileRefusesANewEntryButStillAcceptsOneThatAlreadyExists() {
		AdvisorProperties d = TestProperties.defaults();
		PreferenceService tiny = serviceWith(d.withProfile(new AdvisorProperties.Profile("m", 500, 1, 0.6)));
		state.set(PreferenceProfile.empty().with(extracted(PreferenceCategory.EXCLUDED_SECTORS, "Energy")));

		assertThatThrownBy(() -> tiny.add("u", PreferenceCategory.EXCLUDED_SECTORS, "Utilities")).isInstanceOf(InvalidPreferenceException.class);
		assertThat(tiny.add("u", PreferenceCategory.EXCLUDED_SECTORS, "Energy").value()).isEqualTo("Energy");
	}

	@Test
	void removeIsASoftDeleteDatedNow() {
		PreferenceEntry energy = extracted(PreferenceCategory.EXCLUDED_SECTORS, "Energy");
		state.set(PreferenceProfile.empty().with(energy));

		service.remove("u", energy.id());

		PreferenceEntry stored = state.get().find(PreferenceCategory.EXCLUDED_SECTORS, "Energy").orElseThrow();
		assertThat(stored.status()).isEqualTo(Status.REMOVED_BY_USER);
		assertThat(stored.evidenceAt()).isEqualTo(NOW);
		assertThat(service.active("u")).isEmpty();
	}

	@Test
	void removingAnUnknownOrAlreadyDeletedEntryIsNotFound() {
		PreferenceEntry energy = extracted(PreferenceCategory.EXCLUDED_SECTORS, "Energy").withStatus(Status.REMOVED_BY_USER, EARLIER);
		state.set(PreferenceProfile.empty().with(energy));

		assertThatThrownBy(() -> service.remove("u", energy.id())).isInstanceOf(PreferenceNotFoundException.class);
		assertThatThrownBy(() -> service.remove("u", "nope")).isInstanceOf(PreferenceNotFoundException.class);
	}

	@Test
	void activeEntriesAreGroupedByCategoryThenNewestConfirmedFirst() {
		PreferenceEntry olderGoal = extracted(PreferenceCategory.GOALS, "old goal");
		PreferenceEntry newerGoal = new PreferenceEntry(PreferenceCategory.GOALS, "new goal", Status.ACTIVE, Source.EXTRACTED, NOW, NOW, NOW, "s", "q");
		PreferenceEntry sector = extracted(PreferenceCategory.EXCLUDED_SECTORS, "Energy");
		state.set(PreferenceProfile.empty().with(olderGoal).with(newerGoal).with(sector));

		assertThat(service.active("u")).containsExactly(sector, newerGoal, olderGoal);
		assertThat(List.of(sector)).isNotEmpty();
	}
}
