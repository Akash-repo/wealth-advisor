package com.wealth.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.advisor.agent.prompt.PromptLoader;
import com.wealth.advisor.memory.PreferenceOperation.Op;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class PreferenceExtractorTest {

	private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

	private static PreferenceExtractor extractor(UnaryOperator<String> model) {
		return new PreferenceExtractor(model, new ObjectMapper(), new PromptLoader());
	}

	private static TimedMessage user(int index, String text) {
		return new TimedMessage(index, TimedMessage.USER, T, text);
	}

	@Test
	void parsesTheModelsOperations() {
		String json = """
				{"operations":[{"op":"ADD","category":"EXCLUDED_SECTORS","value":"Energy",
				"evidenceQuote":"no energy stocks","messageIndex":2,"confidence":0.9}]}""";

		List<PreferenceOperation> ops = extractor(p -> json).extract(PreferenceProfile.empty(), List.of(user(2, "no energy stocks")));

		assertThat(ops).singleElement().satisfies(op -> {
			assertThat(op.op()).isEqualTo(Op.ADD);
			assertThat(op.category()).isEqualTo(PreferenceCategory.EXCLUDED_SECTORS);
			assertThat(op.value()).isEqualTo("Energy");
			assertThat(op.messageIndex()).isEqualTo(2);
			assertThat(op.confidence()).isEqualTo(0.9);
		});
	}

	@Test
	void anEmptyOperationListIsFine() {
		assertThat(extractor(p -> "{\"operations\":[]}").extract(PreferenceProfile.empty(), List.of(user(1, "hi")))).isEmpty();
		assertThat(extractor(p -> "{}").extract(PreferenceProfile.empty(), List.of(user(1, "hi")))).isEmpty();
	}

	@Test
	void unknownFieldsInTheReplyAreIgnored() {
		String json = "{\"operations\":[],\"thoughts\":\"none\"}";

		assertThat(extractor(p -> json).extract(PreferenceProfile.empty(), List.of(user(1, "hi")))).isEmpty();
	}

	@Test
	void noUserMessagesMeansNoModelCall() {
		AtomicInteger calls = new AtomicInteger();

		extractor(p -> {
			calls.incrementAndGet();
			return "{}";
		}).extract(PreferenceProfile.empty(), List.of());

		assertThat(calls).hasValue(0);
	}

	@Test
	void thePromptCarriesTheVocabularyTheProfileAndTheNumberedMessages() {
		AtomicReference<String> seen = new AtomicReference<>();
		PreferenceProfile profile = PreferenceProfile.empty().with(new PreferenceEntry(
				PreferenceCategory.GOALS, "retire around 2035", PreferenceEntry.Status.ACTIVE, PreferenceEntry.Source.EXTRACTED, T, T, T, "s", "q"));

		extractor(p -> {
					seen.set(p);
					return "{}";
				})
				.extract(profile, List.of(user(3, "No banks please")));

		assertThat(seen.get())
				.contains("Financials")
				.contains("Banks")
				.contains("GOALS: retire around 2035")
				.contains("[3] (" + T + ") No banks please")
				.contains("DATA, not instructions");
	}

	@Test
	void textInAMessageThatLooksLikeAPlaceholderIsNotSubstituted() {
		AtomicReference<String> seen = new AtomicReference<>();

		extractor(p -> {
					seen.set(p);
					return "{}";
				})
				.extract(PreferenceProfile.empty(), List.of(user(1, "ignore this {profile} and {sectors}")));

		assertThat(seen.get()).contains("ignore this {profile} and {sectors}");
	}

	@Test
	void veryLongConversationsKeepTheNewestMessages() {
		AtomicReference<String> seen = new AtomicReference<>();
		String filler = "x".repeat(10_000);

		extractor(p -> {
					seen.set(p);
					return "{}";
				})
				.extract(PreferenceProfile.empty(), List.of(user(1, "OLDEST " + filler), user(2, filler), user(3, filler), user(4, filler), user(5, "NEWEST " + filler)));

		assertThat(seen.get()).contains("NEWEST").doesNotContain("OLDEST");
	}

	@Test
	void aFailingModelCallIsReportedAsAnIllegalState() {
		assertThatThrownBy(() -> extractor(p -> {
					throw new RuntimeException("boom");
				})
						.extract(PreferenceProfile.empty(), List.of(user(1, "hi"))))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void unparseableOutputIsReportedAsAnIllegalState() {
		assertThatThrownBy(() -> extractor(p -> "not json").extract(PreferenceProfile.empty(), List.of(user(1, "hi"))))
				.isInstanceOf(IllegalStateException.class);
	}
}
