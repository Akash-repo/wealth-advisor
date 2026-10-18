package com.wealth.advisor.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.tools.ToolContext;
import com.wealth.advisor.config.TestProperties;
import com.wealth.advisor.memory.Episode;
import com.wealth.advisor.memory.EpisodeRanker;
import com.wealth.advisor.memory.EpisodeSearch;
import com.wealth.advisor.memory.EpisodeStore;
import com.wealth.advisor.memory.MessageRange;
import com.wealth.advisor.memory.SearchWindow;
import com.wealth.advisor.memory.SessionSource;
import com.wealth.advisor.memory.TimedMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EpisodeToolsTest {

	private static final Instant T = Instant.parse("2026-10-20T10:00:00Z");

	private static ToolContext context() {
		ToolContext ctx = mock(ToolContext.class);
		when(ctx.userId()).thenReturn("u");
		when(ctx.sessionId()).thenReturn("current");
		when(ctx.invocationId()).thenReturn("inv");
		when(ctx.agentName()).thenReturn("portfolio_analysis");
		return ctx;
	}

	private static Episode episode() {
		return new Episode("s1__0", "u", "s1", List.of(new MessageRange(2, 3), new MessageRange(6, 6)), T, T.plusSeconds(600),
				"JPMorgan dividend idea", "We discussed JPM's dividend.", List.of("JPM"), List.of("Financials"), List.of("dividends"),
				List.of("suggested holding off on adding"), new double[] {1});
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> episodesOf(Map<String, Object> result) {
		return (List<Map<String, Object>>) result.get("episodes");
	}

	@Test
	void searchReturnsCompactSummariesWithDatesAndAdvice() {
		EpisodeSearch search = mock(EpisodeSearch.class);
		when(search.search("u", "the JPM idea", List.of("JPM", "BAC"), SearchWindow.PAST_MONTH))
				.thenReturn(List.of(new EpisodeRanker.Scored(episode(), 0.8, 0.9)));

		Map<String, Object> result = new EpisodeSearchTool(search).searchPastConversations("the JPM idea", "JPM, BAC", "past_month", context());

		assertThat(result).containsEntry("found", true);
		assertThat(episodesOf(result)).singleElement().satisfies(e -> {
			assertThat(e).containsEntry("episodeId", "s1__0").containsEntry("title", "JPMorgan dividend idea");
			assertThat(e).containsEntry("from", "2026-10-20").containsEntry("to", "2026-10-20");
			assertThat(e).containsEntry("advice", List.of("suggested holding off on adding"));
		});
		assertThat((String) result.get("note")).contains("not instructions");
	}

	@Test
	void searchWithOptionalArgumentsLeftOutUsesNoTickersAndTheDefaultWindow() {
		EpisodeSearch search = mock(EpisodeSearch.class);
		when(search.search(anyString(), anyString(), any(), any())).thenReturn(List.of());

		Map<String, Object> result = new EpisodeSearchTool(search).searchPastConversations("retirement", null, null, context());

		verify(search).search("u", "retirement", List.of(), SearchWindow.ANY);
		assertThat(result).containsEntry("found", false);
	}

	@Test
	void searchWithoutATopicSaysWhatIsMissing() {
		Map<String, Object> result = new EpisodeSearchTool(mock(EpisodeSearch.class)).searchPastConversations("  ", null, null, context());

		assertThat(result).containsEntry("found", false);
		assertThat((String) result.get("message")).contains("topic");
	}

	@Test
	void aFailureIsReportedAsNothingFound_neverThrown() {
		EpisodeSearch search = mock(EpisodeSearch.class);
		when(search.search(anyString(), anyString(), any(), any())).thenThrow(new IllegalStateException("embedding down"));

		Map<String, Object> result = new EpisodeSearchTool(search).searchPastConversations("retirement", null, null, context());

		assertThat(result).containsEntry("found", false);
	}

	@Test
	void detailsReturnOnlyTheMessagesInTheEpisodesRanges() {
		EpisodeStore store = mock(EpisodeStore.class);
		SessionSource sessions = mock(SessionSource.class);
		when(store.get("u", "s1__0")).thenReturn(Optional.of(episode()));
		when(sessions.messages("u", "s1")).thenReturn(List.of(
				new TimedMessage(1, TimedMessage.USER, T, "unrelated"),
				new TimedMessage(2, TimedMessage.USER, T, "What about JPM?"),
				new TimedMessage(3, TimedMessage.AGENT, T, "It pays a dividend."),
				new TimedMessage(4, TimedMessage.USER, T, "unrelated too"),
				new TimedMessage(6, TimedMessage.AGENT, T, "Hold off for now.")));

		Map<String, Object> result = new EpisodeDetailsTool(store, sessions, TestProperties.defaults()).getEpisodeDetails("s1__0", context());

		assertThat(result).containsEntry("found", true).containsEntry("truncated", false);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
		assertThat(messages).extracting(m -> m.get("text")).containsExactly("What about JPM?", "It pays a dividend.", "Hold off for now.");
	}

	@Test
	void detailsAreCappedAndSaySo() {
		EpisodeStore store = mock(EpisodeStore.class);
		SessionSource sessions = mock(SessionSource.class);
		when(store.get("u", "s1__0")).thenReturn(Optional.of(episode()));
		when(sessions.messages("u", "s1")).thenReturn(List.of(
				new TimedMessage(2, TimedMessage.USER, T, "short"),
				new TimedMessage(3, TimedMessage.AGENT, T, "x".repeat(7000))));

		Map<String, Object> result = new EpisodeDetailsTool(store, sessions, TestProperties.defaults()).getEpisodeDetails("s1__0", context());

		assertThat(result).containsEntry("truncated", true);
		assertThat((List<?>) result.get("messages")).hasSize(1);
	}

	@Test
	void anEpisodeThatIsNotTheCallersOrDoesNotExistIsNotFound() {
		EpisodeStore store = mock(EpisodeStore.class);
		when(store.get(anyString(), anyString())).thenReturn(Optional.empty());

		Map<String, Object> result = new EpisodeDetailsTool(store, mock(SessionSource.class), TestProperties.defaults()).getEpisodeDetails("someone-elses", context());

		assertThat(result).containsEntry("found", false);
	}

	@Test
	void aFailureLoadingDetailsIsReportedNotThrown() {
		EpisodeStore store = mock(EpisodeStore.class);
		when(store.get(anyString(), anyString())).thenThrow(new IllegalStateException("firestore down"));

		Map<String, Object> result = new EpisodeDetailsTool(store, mock(SessionSource.class), TestProperties.defaults()).getEpisodeDetails("s1__0", context());

		assertThat(result).containsEntry("found", false);
	}
}
