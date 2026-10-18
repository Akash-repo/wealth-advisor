package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.wealth.advisor.memory.PreferenceEntry.Source;
import com.wealth.advisor.memory.PreferenceEntry.Status;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The memory stores against a real Firestore, so what unit tests can only mock (transactions, document
 * mapping, queries) is exercised. Run it against the emulator, never the real project: start {@code
 * gcloud emulators firestore start --host-port=localhost:8086}, then run with
 * {@code FIRESTORE_EMULATOR_HOST=localhost:8086 ./mvnw -Dtest=MemoryStoresEmulatorTest test}. Skips itself
 * when that variable is not set.
 */
@Tag("eval")
class MemoryStoresEmulatorTest {

	private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

	private static Firestore firestore;

	@BeforeAll
	static void connect() {
		assumeTrue(System.getenv("FIRESTORE_EMULATOR_HOST") != null, "FIRESTORE_EMULATOR_HOST is not set");
		firestore = FirestoreOptions.newBuilder().setProjectId("memory-test").build().getService();
	}

	/** A store with an empty cache, so reads really go to Firestore. */
	private static PreferenceStore freshStore() {
		return new PreferenceStore(firestore, Clock.systemUTC());
	}

	private static String newUser() {
		return "user-" + UUID.randomUUID();
	}

	private static PreferenceEntry entry(PreferenceCategory category, String value) {
		return new PreferenceEntry(category, value, Status.ACTIVE, Source.EXTRACTED, T, T, T, "s1", "a quote");
	}

	@Test
	void anUnknownUserHasAnEmptyProfile() {
		assertThat(new PreferenceStore(firestore, Clock.systemUTC()).load(newUser()).entries()).isEmpty();
	}

	@Test
	void aSavedProfileIsReadBackUnchanged() {
		PreferenceStore store = new PreferenceStore(firestore, Clock.systemUTC());
		String user = newUser();
		PreferenceEntry energy = entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy");
		PreferenceEntry goal = entry(PreferenceCategory.GOALS, "retire around 2035").withStatus(Status.REMOVED_BY_USER, T.plusSeconds(60));

		store.update(user, p -> p.with(energy).with(goal));

		assertThat(freshStore().load(user).entries()).containsExactly(energy, goal);
	}

	@Test
	void profilesOfDifferentUsersAreSeparate() {
		PreferenceStore store = new PreferenceStore(firestore, Clock.systemUTC());
		String a = newUser();
		String b = newUser();

		store.update(a, p -> p.with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy")));

		assertThat(freshStore().load(b).entries()).isEmpty();
	}

	@Test
	void overlappingUpdatesDoNotLoseEntries() throws Exception {
		PreferenceStore store = new PreferenceStore(firestore, Clock.systemUTC());
		String user = newUser();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			String ticker = "T" + i;
			futures.add(pool.submit(() -> store.update(user, p -> p.with(entry(PreferenceCategory.EXCLUDED_TICKERS, ticker)))));
		}
		for (Future<?> f : futures) {
			f.get();
		}
		pool.shutdown();

		assertThat(freshStore().load(user).entries()).hasSize(12);
	}

	private static Episode episode(String user, String session, int position, String title, Instant end) {
		return new Episode(
				session + "__" + position, user, session, List.of(new MessageRange(1, 3), new MessageRange(7, 8)),
				end.minusSeconds(600), end, title, "Summary of " + title, List.of("JPM"), List.of("Financials"), List.of("dividends"),
				List.of("hold"), new double[] {0.25, -0.5, 1.0});
	}

	@Test
	void anEpisodeIsReadBackWithItsRangesListsAndVector() {
		EpisodeStore store = new EpisodeStore(firestore);
		String user = newUser();
		Episode original = episode(user, "s1", 0, "JPM idea", T);

		store.replaceSession(user, "s1", List.of(original));

		Episode read = store.get(user, "s1__0").orElseThrow();
		assertThat(read).usingRecursiveComparison().ignoringFields("embedding").isEqualTo(original);
		assertThat(read.embedding()).containsExactly(0.25, -0.5, 1.0);
	}

	@Test
	void replacingASessionOverwritesItsEpisodesAndRemovesTheLeftovers() {
		EpisodeStore store = new EpisodeStore(firestore);
		String user = newUser();
		store.replaceSession(user, "s1", List.of(episode(user, "s1", 0, "First", T), episode(user, "s1", 1, "Second", T), episode(user, "s1", 2, "Third", T)));
		store.replaceSession(user, "other", List.of(episode(user, "other", 0, "Elsewhere", T)));

		store.replaceSession(user, "s1", List.of(episode(user, "s1", 0, "First, rewritten", T)));

		assertThat(store.candidates(user, T.minusSeconds(3600))).extracting(Episode::title).containsExactlyInAnyOrder("First, rewritten", "Elsewhere");
	}

	@Test
	void candidatesAreFilteredByUserAndByWhenTheEpisodeEnded() {
		EpisodeStore store = new EpisodeStore(firestore);
		String user = newUser();
		String other = newUser();
		store.replaceSession(user, "s1", List.of(episode(user, "s1", 0, "Recent", T), episode(user, "s1", 1, "Old", T.minusSeconds(86_400L * 100))));
		store.replaceSession(other, "s2", List.of(episode(other, "s2", 0, "Not yours", T)));

		assertThat(store.candidates(user, T.minusSeconds(86_400L * 30))).extracting(Episode::title).containsExactly("Recent");
		assertThat(store.candidates(user, T.minusSeconds(86_400L * 365))).extracting(Episode::title).containsExactlyInAnyOrder("Recent", "Old");
	}

	@Test
	void anEpisodeCannotBeReadByAnotherUser() {
		EpisodeStore store = new EpisodeStore(firestore);
		String owner = newUser();
		store.replaceSession(owner, "s1", List.of(episode(owner, "s1", 0, "Private", T)));

		assertThat(store.get(newUser(), "s1__0")).isEmpty();
		assertThat(store.get(owner, "s1__0")).isPresent();
		assertThat(store.get(owner, "missing")).isEmpty();
	}

	@Test
	void deletingASessionRemovesOnlyItsEpisodes() {
		EpisodeStore store = new EpisodeStore(firestore);
		String user = newUser();
		store.replaceSession(user, "s1", List.of(episode(user, "s1", 0, "Gone", T)));
		store.replaceSession(user, "s2", List.of(episode(user, "s2", 0, "Stays", T)));

		store.deleteSession(user, "s1");

		assertThat(store.candidates(user, T.minusSeconds(3600))).extracting(Episode::title).containsExactly("Stays");
	}

	@Test
	void extractionStateIsRememberedPerUserAndSession() {
		ExtractionStateStore state = new ExtractionStateStore(firestore);
		String user = newUser();
		String other = newUser();

		state.markProcessed(user, "s1", T);
		state.markProcessed(user, "s2", T.plusSeconds(100));
		state.markProcessed(other, "s1", T.plusSeconds(500));

		assertThat(state.processed(user)).containsOnlyKeys("s1", "s2").containsEntry("s1", T).containsEntry("s2", T.plusSeconds(100));
		assertThat(state.processed(other)).containsOnlyKeys("s1");
	}

	@Test
	void markingASessionAgainReplacesItsTimeAndDeleteForgetsIt() {
		ExtractionStateStore state = new ExtractionStateStore(firestore);
		String user = newUser();

		state.markProcessed(user, "s1", T);
		state.markProcessed(user, "s1", T.plusSeconds(10));
		assertThat(state.processed(user)).containsEntry("s1", T.plusSeconds(10)).hasSize(1);

		state.delete(user, "s1");
		assertThat(state.processed(user)).isEmpty();
	}
}
