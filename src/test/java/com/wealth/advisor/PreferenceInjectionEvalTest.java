package com.wealth.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.advisor.memory.PreferenceCategory;
import com.wealth.advisor.memory.PreferenceEntry;
import com.wealth.advisor.memory.PreferenceStore;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Stage 2 of the memory work, end to end: a stored preference profile really reaches both agents'
 * prompts and the model uses it. The profile is written straight to the store (as the memory job or the
 * preferences panel would), then the user asks what they told the advisor; the answer can only come from
 * the injected block, because this user has had no earlier conversation.
 *
 * <p>Also proves the whole application context starts with all the memory beans wired. Real Gemini calls
 * and Firestore, so run against the emulator: see {@link GuardrailEvalTest}. Tagged {@code eval}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Tag("eval")
class PreferenceInjectionEvalTest {

	private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

	@Autowired private TestRestTemplate restTemplate;
	@Autowired private PreferenceStore preferences;

	private EvalHttp http;

	@BeforeEach
	void setUp() {
		http = new EvalHttp(restTemplate);
	}

	private static PreferenceEntry entry(PreferenceCategory category, String value) {
		return new PreferenceEntry(category, value, PreferenceEntry.Status.ACTIVE, PreferenceEntry.Source.MANUAL, T, T, T, null, null);
	}

	@Test
	void theAdvisorKnowsWhatTheProfileSays() {
		EvalHttp.EvalUser user = http.newUser();
		preferences.update(user.name(), p -> p
				.with(entry(PreferenceCategory.EXCLUDED_SECTORS, "Energy"))
				.with(entry(PreferenceCategory.GOALS, "retire around 2035")));

		Map<String, Object> reply = http.chat(user, null, "Which sectors have I told you to avoid, and when do I plan to retire?");

		assertThat(((String) reply.get("reply")).toLowerCase()).contains("energy").contains("2035");
	}

	@Test
	void aUserWithoutAProfileIsAnsweredNormally() {
		EvalHttp.EvalUser user = http.newUser();

		Map<String, Object> reply = http.chat(user, null, "What is an index fund?");

		assertThat((String) reply.get("reply")).isNotBlank();
	}
}
