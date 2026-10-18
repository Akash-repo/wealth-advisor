package com.wealth.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.advisor.memory.EpisodeSearch;
import com.wealth.advisor.memory.SearchWindow;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The whole preference chain through the running app, with real Gemini calls: a user states preferences
 * in a conversation, the background memory job learns them, the preferences API shows them, a brand-new
 * conversation makes use of them, and deleting one removes it.
 *
 * <p>The same background run also turns the conversation into an episode, which is searched here
 * directly.
 *
 * <p>The job normally waits until a conversation has been quiet for {@code advisor.extraction.settle-minutes};
 * the test's clock runs two hours ahead so that wait does not apply. Run against the Firestore emulator,
 * as {@link GuardrailEvalTest} describes. Tagged {@code eval}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(MemoryEndToEndEvalTest.TwoHoursAhead.class)
@Tag("eval")
class MemoryEndToEndEvalTest {

	@TestConfiguration
	static class TwoHoursAhead {
		@Bean
		@Primary
		Clock twoHoursAhead() {
			return Clock.offset(Clock.systemUTC(), Duration.ofHours(2));
		}
	}

	private static final Duration PATIENCE = Duration.ofSeconds(120);

	@Autowired private TestRestTemplate restTemplate;
	@Autowired private EpisodeSearch episodeSearch;

	private EvalHttp http;

	@BeforeEach
	void setUp() {
		http = new EvalHttp(restTemplate);
	}

	private static boolean entry(Map<String, Object> e, String category, Predicate<String> value) {
		return category.equals(e.get("category")) && value.test((String) e.get("value"));
	}

	private List<Map<String, Object>> waitFor(EvalHttp.EvalUser user, Predicate<List<Map<String, Object>>> ready) throws InterruptedException {
		long deadline = System.nanoTime() + PATIENCE.toNanos();
		List<Map<String, Object>> entries = http.preferences(user);
		while (!ready.test(entries) && System.nanoTime() < deadline) {
			Thread.sleep(3000);
			entries = http.preferences(user);
		}
		return entries;
	}

	@Test
	void aPreferenceStatedInAChatIsLearned_shownInThePanel_usedInANewChat_andCanBeDeleted() throws Exception {
		EvalHttp.EvalUser user = http.newUser();
		http.chat(user, null, "I never want energy stocks in my portfolio, and I plan to retire around 2035. Meanwhile, what is an index fund?");

		http.refreshMemory(user);
		List<Map<String, Object>> learned = waitFor(user, entries -> entries.stream().anyMatch(e -> entry(e, "EXCLUDED_SECTORS", "Energy"::equals)));

		assertThat(learned).as("the sector exclusion was learned").anyMatch(e -> entry(e, "EXCLUDED_SECTORS", "Energy"::equals));
		assertThat(learned).as("the goal was learned").anyMatch(e -> entry(e, "GOALS", v -> v.contains("2035")));
		assertThat(learned).allMatch(e -> "extracted".equals(e.get("source")));
		assertThat(episodeSearch.search(user.name(), "what an index fund is", List.of(), SearchWindow.ANY))
				.as("the conversation was summarised into an episode that a search finds")
				.isNotEmpty();

		Map<String, Object> newChat = http.chat(user, null, "Which sectors have I told you to avoid?");
		assertThat(((String) newChat.get("reply")).toLowerCase()).as("a brand-new conversation uses the learned profile").contains("energy");

		String energyId = (String) learned.stream().filter(e -> entry(e, "EXCLUDED_SECTORS", "Energy"::equals)).findFirst().orElseThrow().get("id");
		http.deletePreference(user, energyId);
		assertThat(http.preferences(user)).as("deleted from the panel").noneMatch(e -> entry(e, "EXCLUDED_SECTORS", "Energy"::equals));

		http.refreshMemory(user);
		Thread.sleep(15000);
		assertThat(http.preferences(user)).as("another run does not bring it back").noneMatch(e -> entry(e, "EXCLUDED_SECTORS", "Energy"::equals));
	}
}
