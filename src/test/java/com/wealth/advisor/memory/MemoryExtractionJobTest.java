package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wealth.advisor.config.TestProperties;
import com.wealth.advisor.memory.PreferenceOperation.Op;
import com.wealth.advisor.memory.SessionSource.SessionStamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryExtractionJobTest {

	private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
	private static final Instant LONG_AGO = NOW.minusSeconds(3 * 3600);
	private static final Instant JUST_NOW = NOW.minusSeconds(5 * 60);

	private SessionSource sessions;
	private PreferenceExtractor extractor;
	private PreferenceStore preferences;
	private EpisodeExtractor episodeExtractor;
	private EpisodeStore episodes;
	private ExtractionStateStore state;
	private MemoryExtractionJob job;
	private PreferenceProfile saved;
	private final List<SessionStamp> stamps = new ArrayList<>();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		sessions = mock(SessionSource.class);
		extractor = mock(PreferenceExtractor.class);
		preferences = mock(PreferenceStore.class);
		episodeExtractor = mock(EpisodeExtractor.class);
		episodes = mock(EpisodeStore.class);
		state = mock(ExtractionStateStore.class);
		saved = null;
		stamps.clear();
		when(sessions.list("u")).thenAnswer(invocation -> List.copyOf(stamps));
		when(state.processed(anyString())).thenReturn(Map.of());
		when(preferences.load(anyString())).thenReturn(PreferenceProfile.empty());
		when(preferences.update(anyString(), any())).thenAnswer(invocation -> {
			UnaryOperator<PreferenceProfile> change = invocation.getArgument(1);
			saved = change.apply(PreferenceProfile.empty());
			return saved;
		});
		job = new MemoryExtractionJob(
				sessions,
				extractor,
				new PreferenceReconciler(TestProperties.defaults()),
				preferences,
				episodeExtractor,
				new EpisodeValidator(TestProperties.defaults()),
				new FakeEmbedder(),
				episodes,
				state,
				Clock.fixed(NOW, ZoneOffset.UTC),
				TestProperties.defaults());
	}

	private void session(String id, Instant lastUpdate, String... userTexts) {
		stamps.add(new SessionStamp(id, lastUpdate));
		List<TimedMessage> messages = new ArrayList<>();
		for (int i = 0; i < userTexts.length; i++) {
			messages.add(new TimedMessage(i + 1, TimedMessage.USER, lastUpdate.minusSeconds(60), userTexts[i]));
		}
		when(sessions.messages("u", id)).thenReturn(messages);
	}

	@Test
	void aChangedAndSettledSessionIsProcessedAndMarked() {
		session("a", LONG_AGO, "I want no energy stocks.");
		when(extractor.extract(any(), any())).thenReturn(List.of(new PreferenceOperation(
				Op.ADD, PreferenceCategory.EXCLUDED_SECTORS, "Energy", null, "no energy stocks", 1, 0.9, null)));

		job.refresh("u");

		assertThat(saved.find(PreferenceCategory.EXCLUDED_SECTORS, "Energy")).isPresent();
		verify(state).markProcessed("u", "a", LONG_AGO);
	}

	private static TimedMessage agentReply(int index, Instant at, String text) {
		return new TimedMessage(index, TimedMessage.AGENT, at, text);
	}

	@Test
	void theWholeConversationBecomesEpisodesWithEmbeddings() {
		session("a", LONG_AGO, "What about the JPMorgan bank dividend?");
		List<TimedMessage> both = List.of(
				new TimedMessage(1, TimedMessage.USER, LONG_AGO.minusSeconds(120), "What about the JPMorgan bank dividend?"),
				agentReply(2, LONG_AGO.minusSeconds(60), "It pays a dividend."));
		when(sessions.messages("u", "a")).thenReturn(both);
		when(extractor.extract(any(), any())).thenReturn(List.of());
		when(episodeExtractor.extract(both)).thenReturn(List.of(new EpisodeDraft(
				"JPMorgan dividend", "We discussed the JPMorgan bank dividend.", List.of(new EpisodeDraft.Range(1, 2)),
				List.of("JPM"), List.of("Financials"), List.of(), List.of())));

		job.refresh("u");

		org.mockito.ArgumentCaptor<List<Episode>> stored = org.mockito.ArgumentCaptor.forClass(List.class);
		verify(episodes).replaceSession(eq("u"), eq("a"), stored.capture());
		assertThat(stored.getValue()).singleElement().satisfies(e -> {
			assertThat(e.id()).isEqualTo("a__0");
			assertThat(e.embedding()).isEqualTo(FakeEmbedder.vector("JPMorgan dividend\nWe discussed the JPMorgan bank dividend."));
		});
		verify(state).markProcessed("u", "a", LONG_AGO);
	}

	@Test
	void aSessionWhoseEpisodeExtractionFailsIsNotMarked() {
		session("a", LONG_AGO, "hello there");
		when(extractor.extract(any(), any())).thenReturn(List.of());
		when(episodeExtractor.extract(any())).thenThrow(new IllegalStateException("model down"));

		job.refresh("u");

		verify(episodes, never()).replaceSession(anyString(), anyString(), any());
		verify(state, never()).markProcessed(anyString(), anyString(), any());
	}

	@Test
	void aConversationWithNoUserMessagesProducesNoEpisodes() {
		session("a", LONG_AGO);

		job.refresh("u");

		verify(episodeExtractor, never()).extract(any());
	}

	@Test
	void aSessionThatIsStillActiveIsLeftForALaterRun() {
		session("a", JUST_NOW, "I want no energy stocks.");

		job.refresh("u");

		verify(extractor, never()).extract(any(), any());
		verify(state, never()).markProcessed(anyString(), anyString(), any());
	}

	@Test
	void aSessionThatHasNotChangedSinceItWasProcessedIsSkipped() {
		session("a", LONG_AGO, "I want no energy stocks.");
		when(state.processed("u")).thenReturn(Map.of("a", LONG_AGO));

		job.refresh("u");

		verify(sessions, never()).messages(anyString(), anyString());
		verify(extractor, never()).extract(any(), any());
	}

	@Test
	void aSessionThatChangedAfterItWasProcessedIsProcessedAgain() {
		session("a", LONG_AGO, "I want no energy stocks.");
		when(state.processed("u")).thenReturn(Map.of("a", LONG_AGO.minusSeconds(3600)));
		when(extractor.extract(any(), any())).thenReturn(List.of());

		job.refresh("u");

		verify(extractor).extract(any(), any());
		verify(state).markProcessed("u", "a", LONG_AGO);
	}

	@Test
	void aSessionWithNoUserMessagesIsJustMarked() {
		session("a", LONG_AGO);

		job.refresh("u");

		verify(extractor, never()).extract(any(), any());
		verify(state).markProcessed("u", "a", LONG_AGO);
	}

	@Test
	void aFailingSessionIsNotMarked_andTheOthersStillRun() {
		session("bad", LONG_AGO.minusSeconds(100), "first");
		session("good", LONG_AGO, "second");
		when(extractor.extract(any(), eq(List.of(new TimedMessage(1, TimedMessage.USER, LONG_AGO.minusSeconds(160), "first")))))
				.thenThrow(new IllegalStateException("model down"));
		when(extractor.extract(any(), eq(List.of(new TimedMessage(1, TimedMessage.USER, LONG_AGO.minusSeconds(60), "second")))))
				.thenReturn(List.of());

		job.refresh("u");

		verify(state, never()).markProcessed(eq("u"), eq("bad"), any());
		verify(state).markProcessed("u", "good", LONG_AGO);
	}

	@Test
	void atMostTheConfiguredNumberOfSessionsAreProcessedPerRun_newestFirst() {
		for (int i = 0; i < 12; i++) {
			session("s" + i, LONG_AGO.minusSeconds(1000 - i), "message " + i);
		}
		when(extractor.extract(any(), any())).thenReturn(List.of());

		job.refresh("u");

		verify(state, times(10)).markProcessed(eq("u"), anyString(), any());
		// s11 changed last and s0 first: the ten newest (s2 to s11) are done, the two oldest wait
		verify(state, never()).markProcessed(eq("u"), eq("s0"), any());
		verify(state, never()).markProcessed(eq("u"), eq("s1"), any());
		verify(state).markProcessed(eq("u"), eq("s11"), any());
	}

	@Test
	void aFailureListingSessionsNeverEscapes() {
		when(sessions.list("u")).thenThrow(new IllegalStateException("firestore down"));

		job.refresh("u");

		verify(state, never()).markProcessed(anyString(), anyString(), any());
	}
}
